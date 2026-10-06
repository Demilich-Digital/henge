package digital.demilich.henge.spring;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.RemoteServiceException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The frontend end of the trunks: one websocket to each backend node it opens channels on, however many
 * channels that is, so a backend sees one connection per frontend and not one per end user. A trunk is
 * opened when the first channel needs it, pinged to find out whether it is still there, closed when it has
 * had no channels for a while, and dropped, closing every channel on it, when it fails. See "The trunk"
 * in {@code docs/design/channels.md}.
 *
 * <p>The websocket is the JDK's, which needs no dependency and sends one message at a time, as a trunk's one
 * {@link TrunkWriter} does.
 */
final class TrunkPool implements AutoCloseable {

    private static final Log log = LogFactory.getLog(TrunkPool.class);

    private final HengeProperties.ChannelSettings settings;
    private final Duration connectTimeout;
    private final String secret;
    private final String trunkPath;
    private final HttpClient httpClient;
    private final Map<String, CompletableFuture<Trunk>> trunks = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    /**
     * @param secret sent with the handshake; null or blank for none
     * @param serverPathPrefix {@code henge.server.path-prefix}, which the trunk is served under
     */
    TrunkPool(HengeProperties.ChannelSettings settings, Duration connectTimeout, String secret, String serverPathPrefix) {
        this.settings = settings;
        this.connectTimeout = connectTimeout;
        this.secret = secret == null || secret.isBlank() ? null : secret;
        this.trunkPath = serverPathPrefix + TrunkFrame.PATH;
        HttpClient.Builder client = HttpClient.newBuilder();
        if (!connectTimeout.isZero()) {
            client.connectTimeout(connectTimeout);
        }
        this.httpClient = client.build();
    }

    /** The trunk to {@code baseUrl} could not be opened or has just gone: nothing was sent, so another host may be tried. */
    static final class TrunkUnavailable extends RemoteServiceException {

        TrunkUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Opens a channel on the trunk to {@code baseUrl}, opening the trunk first if there isn't one.
     *
     * @throws TrunkUnavailable if there is no trunk to open it on
     */
    ChannelHandler open(String baseUrl, byte[] openPayload, Channel toClient) {
        for (int attempt = 1; ; attempt++) {
            Trunk trunk = trunk(baseUrl);
            try {
                return trunk.open(openPayload, toClient);
            } catch (TrunkUnavailable e) {
                // Lost between being found and being used: connect afresh, once.
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }

    private Trunk trunk(String baseUrl) {
        CompletableFuture<Trunk> future = trunks.computeIfAbsent(baseUrl, this::connect);
        try {
            return future.get(connectTimeout.isZero() ? Long.MAX_VALUE : connectTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            trunks.remove(baseUrl, future);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new TrunkUnavailable("Couldn't open a trunk to " + baseUrl + " (" + describe(cause) + ")", cause);
        }
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root instanceof TimeoutException ? "timed out" : String.valueOf(root.getMessage());
    }

    private CompletableFuture<Trunk> connect(String baseUrl) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("closed"));
        }
        URI uri = URI.create(webSocketUrl(baseUrl) + trunkPath);
        Trunk trunk = new Trunk(baseUrl);
        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        if (!connectTimeout.isZero()) {
            builder.connectTimeout(connectTimeout);
        }
        if (secret != null) {
            builder.header(HengeDispatcherController.SECRET_HEADER, secret);
        }
        return builder.buildAsync(uri, trunk).thenApply(socket -> {
            trunk.start(socket);
            return trunk;
        });
    }

    /** {@code http://host} is {@code ws://host}, and {@code https://host} is {@code wss://host}. */
    static String webSocketUrl(String baseUrl) {
        if (baseUrl.startsWith("https://")) {
            return "wss://" + baseUrl.substring("https://".length());
        }
        if (baseUrl.startsWith("http://")) {
            return "ws://" + baseUrl.substring("http://".length());
        }
        return baseUrl;
    }

    /** Pings every trunk and closes the idle ones; started when the first trunk is. */
    private synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "henge-trunk-keeper");
                thread.setDaemon(true);
                return thread;
            });
            long period = settings.pingInterval().toMillis();
            scheduler.scheduleWithFixedDelay(this::keep, period, period, TimeUnit.MILLISECONDS);
        }
        return scheduler;
    }

    private void keep() {
        for (CompletableFuture<Trunk> future : trunks.values()) {
            Trunk trunk = future.getNow(null);
            if (trunk != null) {
                try {
                    trunk.keep();
                } catch (RuntimeException e) {
                    log.warn("Keeping a trunk failed", e);
                }
            }
        }
    }

    /** Closes every trunk, and with them the channels on them. */
    @Override
    public synchronized void close() {
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (CompletableFuture<Trunk> future : trunks.values()) {
            Trunk trunk = future.getNow(null);
            if (trunk != null) {
                trunk.lost(new CloseStatus(CloseStatus.SERVICE_RESTART, "this node is shutting down"));
            }
        }
    }

    /** Number of trunks that are connected; for tests and the gauge of open trunks. */
    int trunkCount() {
        return (int) trunks.values().stream().filter(f -> f.getNow(null) != null).count();
    }

    /** One websocket to one backend, carrying many channels. */
    private final class Trunk implements WebSocket.Listener {

        private final String baseUrl;
        private final Map<Long, FrontendChannel> channels = new ConcurrentHashMap<>();
        private final AtomicLong nextChannel = new AtomicLong(1);
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private WebSocket socket;
        private TrunkWriter writer;
        private boolean dead;
        private boolean stopped;
        private volatile long lastPong = System.nanoTime();
        private volatile long idleSince = System.nanoTime();

        Trunk(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        void start(WebSocket webSocket) {
            this.socket = webSocket;
            this.lastPong = System.nanoTime();
            this.writer = new TrunkWriter(this::send, () -> lost(new CloseStatus(CloseStatus.SERVER_ERROR, "backend lost")));
            scheduler();
        }

        private void send(byte[] frame) throws Exception {
            if (frame == TrunkWriter.PING) {
                socket.sendPing(ByteBuffer.allocate(0)).get();
            } else if (frame == TrunkWriter.CLOSE_TRUNK) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "idle").get();
            } else {
                socket.sendBinary(ByteBuffer.wrap(frame), true).get();
            }
        }

        ChannelHandler open(byte[] openPayload, Channel toClient) {
            FrontendChannel channel;
            synchronized (this) {
                if (dead) {
                    throw new TrunkUnavailable("The trunk to " + baseUrl + " has closed", null);
                }
                channel = new FrontendChannel(this, nextChannel.getAndIncrement(), toClient);
                channels.put(channel.number, channel);
            }
            channel.lane.offerControl(new TrunkFrame(TrunkFrame.OPEN, channel.number, openPayload));
            return channel;
        }

        void channelEnded(FrontendChannel channel) {
            channels.remove(channel.number);
            if (channels.isEmpty()) {
                idleSince = System.nanoTime();
            }
        }

        /** Every {@code ping-interval}: the trunk is pinged, and dropped if it hasn't answered in two, or has had no use for a while. */
        void keep() {
            long now = System.nanoTime();
            if (now - lastPong > 2 * settings.pingInterval().toNanos()) {
                lost(new CloseStatus(CloseStatus.SERVER_ERROR, "backend lost: no answer to pings"));
                return;
            }
            synchronized (this) {
                if (!dead && channels.isEmpty() && now - idleSince > settings.idleTimeout().toNanos()) {
                    dead = true;
                    trunks.values().removeIf(future -> future.getNow(null) == this);
                    writer.closeTrunk();
                    return;
                }
            }
            writer.ping();
        }

        /** The trunk is gone: every channel on it closes with {@code status}, and the next open connects afresh. */
        void lost(CloseStatus status) {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                stopped = true;
                dead = true;
            }
            trunks.values().removeIf(future -> future.getNow(null) == this);
            if (writer != null) {
                writer.stop();
            }
            if (socket != null) {
                socket.abort();
            }
            for (FrontendChannel channel : channels.values()) {
                channel.closedByBackend(status);
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                byte[] message = partial.toByteArray();
                partial.reset();
                try {
                    receive(TrunkFrame.decode(ByteBuffer.wrap(message)));
                } catch (IllegalArgumentException e) {
                    log.warn("A trunk to " + baseUrl + " sent something that isn't a frame; dropping it", e);
                    lost(new CloseStatus(CloseStatus.SERVER_ERROR, "backend sent an invalid frame"));
                    return null;
                }
            }
            webSocket.request(1);
            return null;
        }

        private void receive(TrunkFrame frame) {
            FrontendChannel channel = channels.get(frame.channel());
            if (channel == null) {
                return; // closed meanwhile
            }
            switch (frame.type()) {
                case TrunkFrame.TEXT -> channel.deliver(() -> channel.toClient.sendText(frame.text()));
                case TrunkFrame.BINARY -> channel.deliver(() -> channel.toClient.sendBinary(frame.payload()));
                case TrunkFrame.CLOSE -> channel.closedByBackend(frame.closeStatus());
                default -> { }
            }
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPong = System.nanoTime();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            lost(new CloseStatus(CloseStatus.SERVER_ERROR, "backend lost: trunk closed (" + statusCode + ")"));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("The trunk to " + baseUrl + " failed", error);
            lost(new CloseStatus(CloseStatus.SERVER_ERROR, "backend lost"));
        }
    }

    /** What a caller holds for an open channel: its handler, whose frames go to the backend on the trunk. */
    private final class FrontendChannel implements ChannelHandler {

        private final Trunk trunk;
        private final long number;
        private final Channel toClient;
        private final TrunkWriter.Lane lane;
        private final Mailbox inbound = new Mailbox(settings.queueSize());
        private final AtomicBoolean closed = new AtomicBoolean();

        FrontendChannel(Trunk trunk, long number, Channel toClient) {
            this.trunk = trunk;
            this.number = number;
            this.toClient = toClient;
            this.lane = trunk.writer.lane(settings.queueSize());
        }

        @Override
        public void onText(String text) {
            if (text.length() * 3 > settings.maxFrameBytes() && text.getBytes(StandardCharsets.UTF_8).length > settings.maxFrameBytes()) {
                tooBig();
            } else {
                send(TrunkFrame.text(number, text));
            }
        }

        @Override
        public void onBinary(byte[] data) {
            if (data.length > settings.maxFrameBytes()) {
                tooBig();
            } else {
                send(TrunkFrame.binary(number, data));
            }
        }

        private void tooBig() {
            fail(new CloseStatus(CloseStatus.TOO_BIG, "frame larger than " + settings.maxFrameBytes() + " bytes"));
        }

        private void send(TrunkFrame frame) {
            if (closed.get()) {
                return;
            }
            if (!lane.offer(frame)) {
                fail(new CloseStatus(CloseStatus.TRY_AGAIN_LATER, "too many frames queued for the backend"));
            }
        }

        /** The caller closed it: the backend is told. */
        @Override
        public void onClose(CloseStatus status) {
            if (end()) {
                lane.offerControl(TrunkFrame.close(number, status));
            }
        }

        /** This end gave up on the channel: the backend and the client are both told. */
        private void fail(CloseStatus status) {
            if (end()) {
                lane.offerControl(TrunkFrame.close(number, status));
                toClient.close(status);
            }
        }

        /** Queues what the backend sent for the client, in order; a full queue closes the channel. */
        void deliver(Runnable task) {
            if (!closed.get() && !inbound.offer(task)) {
                fail(new CloseStatus(CloseStatus.TRY_AGAIN_LATER, "too many frames queued for the client"));
            }
        }

        /** The backend closed it, or the trunk was lost: the client is told, after the frames queued before. */
        void closedByBackend(CloseStatus status) {
            if (!end()) {
                return;
            }
            Runnable notify = () -> toClient.close(status);
            if (!inbound.offer(notify)) {
                Thread.ofVirtual().start(notify);
            }
        }

        private boolean end() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            trunk.channelEnded(this);
            return true;
        }
    }
}
