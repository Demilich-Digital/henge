package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.feed.FeedTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
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

/**
 * The trunk is a {@code GET} websocket handshake, which Spring Security's default chain would refuse: the
 * backend here runs with Spring Security, a shared secret and a non-root servlet path, and the trunk still
 * opens for a frontend with the secret and only for it.
 */
class ChannelsSecurityTest {

    private static ConfigurableApplicationContext backend;
    private static ConfigurableApplicationContext frontend;

    private static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @BeforeAll
    static void startBoth() {
        // Spring Security stays on for the backend: that is what the test is about.
        backend = new SpringApplicationBuilder(FeedTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "spring.mvc.servlet.path=/api",
                        "henge.transport.secret=s3cr3t")
                .run();
        frontend = new SpringApplicationBuilder(FeedTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                        "henge.transport.secret=s3cr3t",
                        "henge.services.feed-service.mode=internal-rest",
                        "henge.services.feed-service.url=http://localhost:" + portOf(backend) + "/api")
                .run();
    }

    @AfterAll
    static void stopBoth() {
        frontend.close();
        backend.close();
    }

    private static final class Frames implements WebSocket.Listener {

        final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(4);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            received.add(data.toString());
            webSocket.request(1);
            return null;
        }
    }

    private static int handshakeStatus(String secret) {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (secret != null) {
            builder.header("Henge-Internal-Secret", secret);
        }
        try {
            WebSocket socket = builder.buildAsync(
                    URI.create("ws://localhost:" + portOf(backend) + "/api/_henge/_trunk"), new WebSocket.Listener() { })
                    .orTimeout(10, TimeUnit.SECONDS).join();
            socket.abort();
            return 101;
        } catch (CompletionException e) {
            return ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
        }
    }

    @Test
    void aFrontendWithTheSecretOpensChannelsThroughSpringSecurity() throws Exception {
        var frames = new Frames();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + portOf(frontend) + "/ws/feed?orders"), frames)
                .orTimeout(10, TimeUnit.SECONDS).join();

        assertThat(frames.received.poll(10, TimeUnit.SECONDS)).isEqualTo("watching orders");
    }

    @Test
    void theTrunkRefusesAHandshakeWithoutTheSecret() {
        assertThat(handshakeStatus(null)).isEqualTo(403);
    }

    @Test
    void theTrunkRefusesAHandshakeWithTheWrongSecret() {
        assertThat(handshakeStatus("wrong")).isEqualTo(403);
    }

    @Test
    void theTrunkAcceptsAHandshakeWithTheSecret() {
        assertThat(handshakeStatus("s3cr3t")).isEqualTo(101);
    }
}
