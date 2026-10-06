package digital.demilich.henge.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * The backend end of a trunk: one websocket from a frontend, carrying many channels. For each {@code OPEN}
 * it looks the method up in the {@link HengeServiceRegistry} (nothing outside that table is reachable),
 * opens the channel through the service's binding and keeps the handler it returns; after that it only
 * moves frames. Each channel's inbound frames wait in its own {@link Mailbox}, so a slow handler stalls
 * only its own channel; its outbound ones in its own lane of the trunk's one {@link TrunkWriter}.
 */
class TrunkServer extends BinaryWebSocketHandler {

    private static final Log log = LogFactory.getLog(TrunkServer.class);
    private static final String SIDE = "backend";
    private static final String CONNECTION = TrunkServer.class.getName() + ".connection";

    private final HengeServiceRegistry registry;
    private final ObjectMapper objectMapper;
    private final HengeProperties.ChannelSettings settings;
    private final SystemMetrics metrics;
    private final AtomicInteger trunks = new AtomicInteger();

    /** How many frontends are connected: one trunk each. */
    int trunks() {
        return trunks.get();
    }

    TrunkServer(HengeServiceRegistry registry, ObjectMapper objectMapper, HengeProperties.ChannelSettings settings,
            SystemMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setBinaryMessageSizeLimit(settings.maxFrameBytes() + TrunkFrame.HEADER_BYTES);
        session.getAttributes().put(CONNECTION, new Connection(session));
        trunks.incrementAndGet();
        metrics.trunkOpened(SIDE);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        Connection connection = (Connection) session.getAttributes().get(CONNECTION);
        TrunkFrame frame;
        try {
            frame = TrunkFrame.decode(message.getPayload());
        } catch (IllegalArgumentException e) {
            session.close(new org.springframework.web.socket.CloseStatus(1002, "not a trunk frame"));
            return;
        }
        connection.receive(frame);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("A trunk failed", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        Connection connection = (Connection) session.getAttributes().get(CONNECTION);
        if (connection != null) {
            trunks.decrementAndGet();
            metrics.trunkClosed(SIDE);
            connection.lost();
        }
    }

    /** One trunk, from one frontend. */
    private final class Connection {

        private final WebSocketSession session;
        private final TrunkWriter writer;
        private final Map<Long, BackendChannel> channels = new ConcurrentHashMap<>();

        Connection(WebSocketSession session) {
            this.session = session;
            this.writer = new TrunkWriter(frame -> session.sendMessage(new BinaryMessage(frame)), this::sendFailed);
        }

        void receive(TrunkFrame frame) {
            if (frame.type() == TrunkFrame.OPEN) {
                BackendChannel channel = new BackendChannel(this, frame.channel());
                if (channels.putIfAbsent(frame.channel(), channel) != null) {
                    return; // a frontend never reuses an id; ignore it rather than disturb the one that has it
                }
                channel.deliver(() -> open(channel, frame.payload()));
                return;
            }
            BackendChannel channel = channels.get(frame.channel());
            if (channel == null) {
                return; // closed meanwhile
            }
            switch (frame.type()) {
                case TrunkFrame.TEXT -> channel.deliver(() -> channel.handler.onText(frame.text()));
                case TrunkFrame.BINARY -> channel.deliver(() -> channel.handler.onBinary(frame.payload()));
                case TrunkFrame.CLOSE -> channel.closedByClient(frame.closeStatus());
                default -> { }
            }
        }

        /** The frontend went away: every channel on it closes. */
        void lost() {
            writer.stop();
            for (BackendChannel channel : channels.values()) {
                channel.closedByClient(new CloseStatus(1001, "frontend gone"));
            }
        }

        private void sendFailed() {
            try {
                session.close(new org.springframework.web.socket.CloseStatus(1011, "send failed"));
            } catch (IOException | RuntimeException e) {
                log.debug("Closing a trunk failed", e);
            }
        }

        private void open(BackendChannel channel, byte[] payload) {
            try {
                TrunkOpen.Request request = TrunkOpen.read(objectMapper, payload, this::resolve);
                channel.reportOpened(request.service(), request.version());
                HengeServiceDescriptor descriptor = registry.find(request.service(), request.version()).orElseThrow();
                Object[] args = request.args();
                args[args.length - 1] = channel;
                channel.handler = registry.binding(descriptor).openLocalChannel(request.method(), args);
            } catch (HengeDispatchException e) {
                channel.close(CloseStatus.forErrorStatus(e.getStatus().value(), e.getMessage()));
                return;
            } catch (Throwable t) {
                if (t instanceof Error error && !(t instanceof StackOverflowError)) {
                    throw error;
                }
                channel.close(ClientChannels.closeStatusFor(t));
                return;
            }
            channel.opened();
        }

        private Method resolve(String service, int version, String method) {
            HengeServiceDescriptor descriptor = registry.find(service, version).orElseThrow(() -> new HengeDispatchException(
                    HttpStatus.NOT_FOUND, "This process does not host Henge service '" + service + "' version '" + version + "'"));
            Method target = descriptor.methods().get(method);
            if (target == null) {
                throw new HengeDispatchException(HttpStatus.NOT_FOUND, "Henge service '" + service + "' has no method '" + method + "'");
            }
            if (!HengeServiceDescriptor.isChannelMethod(target)) {
                throw new HengeDispatchException(HttpStatus.BAD_REQUEST,
                        "Henge service '" + service + "#" + method + "' does not open a channel");
            }
            return target;
        }
    }

    /** What the service sees as the client: frames sent here go to the frontend on the trunk. */
    private final class BackendChannel implements Channel {

        private final Connection connection;
        private final long number;
        private final TrunkWriter.Lane lane;
        private final Mailbox inbound = new Mailbox(settings.queueSize());
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ChannelHandler handler;
        private volatile CloseStatus closedWith;
        private volatile String service;
        private volatile int version;

        BackendChannel(Connection connection, long number) {
            this.connection = connection;
            this.number = number;
            this.lane = connection.writer.lane(settings.queueSize());
        }

        @Override
        public String id() {
            return connection.session.getId() + "/" + number;
        }

        @Override
        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public void sendText(String text) {
            // Measured in UTF-8, as it travels; a string can't be longer in bytes than 3 times its length.
            if (text.length() * 3 > settings.maxFrameBytes() && text.getBytes(StandardCharsets.UTF_8).length > settings.maxFrameBytes()) {
                tooBig();
            } else {
                send(TrunkFrame.text(number, text));
            }
        }

        @Override
        public void sendBinary(byte[] data) {
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
                fail(new CloseStatus(CloseStatus.TRY_AGAIN_LATER, "the client is not keeping up"));
            }
        }

        /**
         * The service closed it (or the binding did, retiring): the frontend is told. The handler is told by
         * the binding's wrapper, which is what the service holds.
         */
        @Override
        public void close(CloseStatus status) {
            if (end(status)) {
                lane.offerControl(TrunkFrame.close(number, status));
            }
        }

        /** This end gave up on the channel (overflow, a frame too large): the frontend and the handler are told. */
        private void fail(CloseStatus status) {
            if (!end(status)) {
                return;
            }
            lane.offerControl(TrunkFrame.close(number, status));
            ChannelHandler current = handler;
            if (current != null) {
                // Not on the caller's thread, which may be the service's own, inside a send.
                Thread.ofVirtual().start(() -> current.onClose(status));
            }
        }

        /** The open names its service: from here the channel is counted, and counted closed when it ends. */
        void reportOpened(String openedService, int openedVersion) {
            service = openedService;
            version = openedVersion;
            metrics.channelOpened(openedService, openedVersion, SIDE);
        }

        private boolean end(CloseStatus status) {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            closedWith = status;
            if (service != null) {
                metrics.channelClosed(service, version, SIDE, status.code());
            }
            connection.channels.remove(number);
            return true;
        }

        /** The service's handler is known; if the channel closed while it was being opened, it is told now. */
        void opened() {
            CloseStatus status = closedWith;
            if (status != null) {
                handler.onClose(status);
            }
        }

        /** Queues work for the handler, in order; a full queue closes the channel. */
        void deliver(Runnable task) {
            if (closed.get()) {
                return;
            }
            if (!inbound.offer(task)) {
                fail(new CloseStatus(CloseStatus.TRY_AGAIN_LATER, "too many frames queued"));
            }
        }

        /** The frontend closed it (or went away): the handler is told, after the frames queued before. */
        void closedByClient(CloseStatus status) {
            if (!end(status)) {
                return;
            }
            Runnable notify = () -> {
                ChannelHandler current = handler;
                if (current != null) {
                    current.onClose(status);
                }
            };
            if (!inbound.offer(notify)) {
                Thread.ofVirtual().start(notify);
            }
        }
    }
}
