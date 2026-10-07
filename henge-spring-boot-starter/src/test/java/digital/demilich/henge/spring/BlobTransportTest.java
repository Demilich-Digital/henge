package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ImmutableBytes;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.spring.fixture.blob.BlobService;
import digital.demilich.henge.spring.fixture.blob.BlobService.Sheet;
import digital.demilich.henge.spring.fixture.blob.BlobTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Binary data across a real split: what round-trips, and what the body limit refuses on each side. */
class BlobTransportTest {

    private ConfigurableApplicationContext server;
    private ConfigurableApplicationContext client;

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private int startServer(String... properties) {
        server = new SpringApplicationBuilder(BlobTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .properties(properties)
                .run();
        return ((ServletWebServerApplicationContext) server).getWebServer().getPort();
    }

    private BlobService startClient(int serverPort, String... properties) {
        client = new SpringApplicationBuilder(BlobTestApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "henge.server.enabled=false", "henge.store.type=in-process",
                        "henge.services.blob-service.mode=internal-rest",
                        "henge.services.blob-service.url=http://localhost:" + serverPort)
                .properties(properties)
                .run();
        return client.getBean(BlobService.class);
    }

    @Test
    void bytesAndRecordsHoldingThemCrossTheSplit() {
        BlobService blobs = startClient(startServer());
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }

        ImmutableBytes reversed = blobs.reverse(ImmutableBytes.copyOf(everyByte));
        assertThat(reversed.get(0)).isEqualTo((byte) 255);
        assertThat(reversed.get(255)).isEqualTo((byte) 0);
        assertThat(blobs.reverse(ImmutableBytes.of())).isEqualTo(ImmutableBytes.of());

        Sheet sheet = new Sheet("hero", ImmutableBytes.of(1, 2, 3));
        assertThat(blobs.rename(sheet, "villain")).isEqualTo(new Sheet("villain", sheet.png()));
    }

    @Test
    void aBodyJustUnderTheLimitCrossesAndTheDefaultLimitIsTenMebibytes() {
        BlobService blobs = startClient(startServer());

        // 7 MiB of zeros is 9.3 MiB of base64: under 10 MiB, in both directions.
        assertThat(blobs.reverse(blobs.zeros(7 * 1024 * 1024)).length()).isEqualTo(7 * 1024 * 1024);
    }

    @Test
    void aCallerRefusesToSendAnArgumentOverItsLimit() {
        BlobService blobs = startClient(startServer(), "henge.transport.max-body-bytes=1000");

        assertThatThrownBy(() -> blobs.reverse(ImmutableBytes.copyOf(new byte[1000])))
                .isInstanceOf(RemoteServiceException.class)
                .hasMessageContaining("blob-service#reverse")
                .hasMessageContaining("not sent")
                .hasMessageContaining("henge.transport.max-body-bytes (1000 bytes)");
        assertThat(blobs.reverse(ImmutableBytes.copyOf(new byte[500])).length()).isEqualTo(500);
    }

    @Test
    void aServerAnswers413ToARequestOverItsLimitWithoutRunningTheMethod() {
        int port = startServer("henge.transport.max-body-bytes=1000");
        BlobService blobs = startClient(port); // its own limit is the default, so it sends

        assertThatThrownBy(() -> blobs.reverse(ImmutableBytes.copyOf(new byte[1000])))
                .isInstanceOf(RemoteServiceException.class)
                .hasMessageContaining("413")
                .hasMessageContaining("henge.transport.max-body-bytes (1000 bytes)");
    }

    @Test
    void aServerReadsNoMoreThanItsLimitOfAnOversizedBody() throws Exception {
        int port = startServer("henge.transport.max-body-bytes=1000");
        String body = "[\"" + "A".repeat(100_000) + "\"]";

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/_henge/blob-service/1/reverse"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("Request body for reverse is over henge.transport.max-body-bytes");
    }

    @Test
    void aCallerRefusesAResponseOverItsLimit() {
        BlobService blobs = startClient(startServer(), "henge.transport.max-body-bytes=1000");

        assertThatThrownBy(() -> blobs.zeros(1000))
                .isInstanceOf(RemoteServiceException.class)
                .hasMessageContaining("blob-service#zeros")
                .hasMessageContaining("answered with a body over henge.transport.max-body-bytes (1000 bytes)");
        assertThat(blobs.zeros(500).length()).isEqualTo(500);
    }

    @Test
    void anInvalidLimitFailsAtStartupNamingTheProperty() {
        assertThatThrownBy(() -> startServer("henge.transport.max-body-bytes=10MB"))
                .hasRootCauseMessage("henge.transport.max-body-bytes=10MB is not a positive integer");
    }
}
