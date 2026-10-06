package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.feed.FeedServiceImpl;
import digital.demilich.henge.spring.fixture.feed.FeedTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A monolith: the websocket a client opens reaches the service's handler and back, through
 * {@link ClientChannels#bridge}, with the service embedded and no network between them.
 */
class ChannelsMonolithTest {

    private static ConfigurableApplicationContext app;
    private static int port;

    /** A browser's end: what it receives, and how the connection ended. */
    private static final class Client implements WebSocket.Listener {

        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(16);
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
    }

    private static ConfigurableApplicationContext startApp() {
        // Spring Security is on this module's test classpath; its default would answer the handshake 401.
        return new SpringApplicationBuilder(FeedTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration")
                .run();
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @BeforeAll
    static void start() {
        app = startApp();
        port = portOf(app);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @BeforeEach
    void reset() {
        FeedServiceImpl.EVENTS.clear();
    }

    private static WebSocket connect(int port, String topic, Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/feed?" + topic), client)
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static String next(BlockingQueue<String> frames) throws InterruptedException {
        return frames.poll(10, TimeUnit.SECONDS);
    }

    @Test
    void aClientWebsocketReachesTheServicesHandlerAndBack() throws Exception {
        var client = new Client();
        WebSocket socket = connect(port, "orders", client);

        assertThat(next(client.received)).isEqualTo("watching orders");
        socket.sendText("hello", true).join();
        assertThat(next(client.received)).isEqualTo("echo:hello");

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(1000);
        awaitEvent("close:1000");
        assertThat(FeedServiceImpl.EVENTS).contains("text:hello");
    }

    @Test
    void aRefusedOpenClosesTheClientWithTheExceptionsStatus() throws Exception {
        var client = new Client();
        connect(port, "missing", client);

        assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(4404);
    }

    @Test
    void theServiceClosingTheChannelClosesTheClient() throws Exception {
        var client = new Client();
        connect(port, "orders", client);
        assertThat(next(client.received)).isEqualTo("watching orders");

        FeedServiceImpl.LAST_CHANNEL.get().close(new digital.demilich.henge.core.CloseStatus(1000, "done"));

        assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    @Test
    void retiringTheServiceClosesItsClientsAsARestart() throws Exception {
        // Its own application: a retired service stays retired.
        try (ConfigurableApplicationContext own = startApp()) {
            var client = new Client();
            connect(portOf(own), "orders", client);
            assertThat(next(client.received)).isEqualTo("watching orders");

            own.getBean(HengeServiceRegistry.class).retire("feed-service", 1, Duration.ZERO, Duration.ofSeconds(5));

            assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(1012);
        }
    }

    private static void awaitEvent(String event) throws InterruptedException {
        for (int i = 0; i < 100 && !FeedServiceImpl.EVENTS.contains(event); i++) {
            Thread.sleep(50);
        }
        assertThat(FeedServiceImpl.EVENTS).contains(event);
    }
}
