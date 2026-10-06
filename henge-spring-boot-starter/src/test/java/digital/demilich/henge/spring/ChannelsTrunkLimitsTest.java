package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.feed.FeedServiceImpl;
import digital.demilich.henge.spring.fixture.feed.FeedTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** What bounds a channel: a service that sends faster than the trunk carries is closed, and nobody else is. */
class ChannelsTrunkLimitsTest {

    private static ConfigurableApplicationContext backend;
    private static ConfigurableApplicationContext frontend;

    private static final class Client implements WebSocket.Listener {

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
    }

    private static ConfigurableApplicationContext start(String... properties) {
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of("server.port=0", "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"));
        all.addAll(java.util.List.of(properties));
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
        backend = start("henge.channels.queue-size=4");
        frontend = start(
                "henge.services.feed-service.mode=internal-rest",
                "henge.services.feed-service.url=http://localhost:" + portOf(backend));
    }

    @AfterAll
    static void stopBoth() {
        frontend.close();
        backend.close();
    }

    private static WebSocket connect(String topic, Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + portOf(frontend) + "/ws/feed?" + topic), client)
                .orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    void aServiceThatFloodsItsChannelIsClosedAsOverloadedWithoutDisturbingAnother() throws Exception {
        var calm = new Client();
        WebSocket calmSocket = connect("calm", calm);
        assertThat(calm.received.poll(10, TimeUnit.SECONDS)).isEqualTo("watching calm");

        var flooded = new Client();
        connect("flood", flooded);

        assertThat(flooded.closed.poll(30, TimeUnit.SECONDS)).isEqualTo(1013);
        // Some of the frames got through before the queue was full, and not all 20000 did.
        assertThat(flooded.received.size()).isBetween(1, 19_999);

        // The other channel on the same trunk still works.
        calmSocket.sendText("still here", true).join();
        assertThat(calm.received.poll(10, TimeUnit.SECONDS)).isEqualTo("echo:still here");
        assertThat(backend.getBean(TrunkServer.class).trunks()).isEqualTo(1);
        // The backend's handler is told too, a moment after the frontend.
        for (int i = 0; i < 100 && !FeedServiceImpl.EVENTS.contains("close:1013"); i++) {
            Thread.sleep(50);
        }
        assertThat(FeedServiceImpl.EVENTS).contains("close:1013");
    }
}
