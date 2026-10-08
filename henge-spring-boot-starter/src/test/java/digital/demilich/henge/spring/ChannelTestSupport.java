package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.feed.FeedTestApp;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** What the channel tests share: a browser's end of a websocket, and applications to put it in front of. */
final class ChannelTestSupport {

    private ChannelTestSupport() {
    }

    /** A browser's end: the text it received, and the status it was closed with. */
    static final class Client implements WebSocket.Listener {

        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            received.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.add(statusCode);
            return null;
        }

        String nextText() throws InterruptedException {
            return received.poll(10, TimeUnit.SECONDS);
        }

        Integer nextClose() throws InterruptedException {
            return closed.poll(10, TimeUnit.SECONDS);
        }
    }

    /** The feed application, Spring Security off (its default would answer a client's handshake 401). */
    static ConfigurableApplicationContext start(String... properties) {
        return start(null, properties);
    }

    /** As {@link #start(String...)}, with {@code store} as the application's own datastore. */
    static ConfigurableApplicationContext start(SystemEphemeralDatastore store, String... properties) {
        return start(store, null, properties);
    }

    /** As {@link #start(SystemEphemeralDatastore, String...)}, with {@code meters} as the application's own registry. */
    static ConfigurableApplicationContext start(SystemEphemeralDatastore store, MeterRegistry meters, String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"));
        all.addAll(List.of(properties));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(FeedTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(all.toArray(String[]::new));
        if (store != null) {
            builder.initializers(context -> context.getBeanFactory().registerSingleton("sharedStore", store));
        }
        if (meters != null) {
            builder.initializers(context -> context.getBeanFactory().registerSingleton("meters", meters));
        }
        return builder.run();
    }

    static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A client's websocket to {@code /ws/feed?topic} on {@code frontend}. */
    static WebSocket connect(ConfigurableApplicationContext frontend, String topic, Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + portOf(frontend) + "/ws/feed?" + topic), client)
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    /** The status a websocket handshake to {@code url} is answered with: 101 if it succeeds. */
    static int handshakeStatus(String url) {
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() { })
                    .orTimeout(10, TimeUnit.SECONDS).join().abort();
            return 101;
        } catch (CompletionException e) {
            return ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
        }
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** The in-process store, which can be made unreachable, or wiped as a restart would. */
    static final class SwitchableStore implements SystemEphemeralDatastore {

        private volatile InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();
        volatile boolean down;

        /**
         * This store as one more node sees it: the same state and outages, but advertisements it writes are
         * its own, not a replacement of another node's (they would otherwise share one node id).
         */
        SystemEphemeralDatastore node(String name) {
            SwitchableStore shared = this;
            return new SystemEphemeralDatastore() {
                @Override
                public String nodeId() {
                    return shared.nodeId();
                }

                @Override
                public void put(String key, String localName, byte[] value, Duration ttl) {
                    shared.put(key, localName + "#" + name, value, ttl);
                }

                @Override
                public void remove(String key, String localName) {
                    shared.remove(key, localName + "#" + name);
                }

                @Override
                public Snapshot read(String key) {
                    return shared.read(key);
                }

                @Override
                public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
                    return shared.claim(key, localName + "#" + name, amount, capacity, ttl);
                }

                @Override
                public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
                    return shared.tryAcquire(key, amount, limit);
                }
            };
        }

        void wipe() {
            delegate = new InProcessEphemeralDatastore();
        }

        private InProcessEphemeralDatastore reachable() {
            if (down) {
                throw new StoreUnavailableException("Can't connect to the store");
            }
            return delegate;
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            reachable().put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            reachable().remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return reachable().read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return reachable().claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
            return reachable().tryAcquire(key, amount, limit);
        }
    }
}
