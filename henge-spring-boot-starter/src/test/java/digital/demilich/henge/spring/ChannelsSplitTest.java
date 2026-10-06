package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.feed.FeedServiceImpl;
import digital.demilich.henge.spring.fixture.feed.FeedTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
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
 * Two processes of the same application, one hosting the feed service and one only reaching it: the
 * client's websocket ends at the frontend, which opens a channel on the backend over a trunk, and however
 * many clients there are the backend holds one connection for them.
 */
class ChannelsSplitTest {

    private static ConfigurableApplicationContext backend;
    private static ConfigurableApplicationContext frontend;

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

    private static ConfigurableApplicationContext start(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off",
                // Spring Security is on this module's test classpath; its default would answer the handshake 401.
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(FeedTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(all.toArray(String[]::new))
                .run();
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @BeforeAll
    static void startBoth() {
        backend = start();
        frontend = start(
                "henge.store.type=in-process", "henge.services.feed-service.mode=internal-rest",
                "henge.services.feed-service.url=http://localhost:" + portOf(backend));
    }

    @AfterAll
    static void stopBoth() {
        frontend.close();
        backend.close();
    }

    @BeforeEach
    void reset() {
        FeedServiceImpl.EVENTS.clear();
    }

    private static WebSocket connect(String topic, Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + portOf(frontend) + "/ws/feed?" + topic), client)
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static String next(BlockingQueue<String> frames) throws InterruptedException {
        return frames.poll(10, TimeUnit.SECONDS);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void manyClientsShareOneTrunkAndEachGetsItsOwnChannel() throws Exception {
        int clients = 25;
        List<Client> listeners = new ArrayList<>();
        List<WebSocket> sockets = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            var client = new Client();
            listeners.add(client);
            sockets.add(connect("topic" + i, client));
        }

        for (int i = 0; i < clients; i++) {
            assertThat(next(listeners.get(i).received)).isEqualTo("watching topic" + i);
            sockets.get(i).sendText("hello" + i, true).join();
        }
        for (int i = 0; i < clients; i++) {
            assertThat(next(listeners.get(i).received)).isEqualTo("echo:hello" + i);
        }

        // Many sockets at the frontend, one connection at the backend.
        assertThat(backend.getBean(TrunkServer.class).trunks()).isEqualTo(1);

        for (WebSocket socket : sockets) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        }
        await(() -> FeedServiceImpl.EVENTS.stream().filter(e -> e.equals("close:1000")).count() == clients);
    }

    @Test
    void aRefusedOpenReachesTheClientAsTheExceptionsStatus() throws Exception {
        var client = new Client();
        connect("missing", client);

        assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(4404);
    }

    @Test
    void theBackendClosingTheChannelClosesTheClientsSocket() throws Exception {
        var client = new Client();
        connect("orders", client);
        assertThat(next(client.received)).isEqualTo("watching orders");

        FeedServiceImpl.LAST_CHANNEL.get().close(new digital.demilich.henge.core.CloseStatus(1000, "done"));

        assertThat(client.closed.poll(10, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    @Test
    void aBinaryFrameCrossesBothHops() throws Exception {
        var received = new LinkedBlockingQueue<byte[]>();
        var listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(16);
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                received.add(bytes);
                webSocket.request(1);
                return null;
            }
        };
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + portOf(frontend) + "/ws/feed?orders"), listener)
                .orTimeout(10, TimeUnit.SECONDS).join();

        socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}), true).join();

        assertThat(received.poll(10, TimeUnit.SECONDS)).containsExactly(1, 2, 3);
    }
}
