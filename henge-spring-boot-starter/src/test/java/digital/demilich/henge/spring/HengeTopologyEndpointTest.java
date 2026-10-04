package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.spring.fixture.multiversion.MultiVersionTestApp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * The topology endpoint and page on a real server, with Spring Security on the classpath: off unless
 * asked for, the JSON held to the shared secret, the page open so it can ask for it.
 */
class HengeTopologyEndpointTest {

    private record Response(int status, String contentType, String contentSecurityPolicy, String body) {
        JsonNode json() throws Exception {
            return new ObjectMapper().readTree(body);
        }
    }

    private static ConfigurableApplicationContext start(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off"));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(MultiVersionTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(all.toArray(String[]::new))
                .run();
    }

    private static Response get(ConfigurableApplicationContext server, String path, String secret) {
        int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
        RestClient.RequestHeadersSpec<?> request = RestClient.create("http://localhost:" + port).get().uri(path);
        if (secret != null) {
            request = request.header(HengeDispatcherController.SECRET_HEADER, secret);
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                String.valueOf(res.getHeaders().getContentType()), res.getHeaders().getFirst("Content-Security-Policy"),
                new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void itIsOffUnlessEnabled() {
        try (var server = start()) {
            assertThat(get(server, "/_henge/topology", null).status()).isNotEqualTo(200);
            assertThat(get(server, "/_henge/topology/ui", null).status()).isNotEqualTo(200);
        }
    }

    @Test
    void theJsonDescribesEveryVersionAndWhoInjectsIt() throws Exception {
        try (var server = start("henge.topology.enabled=true")) {
            Response response = get(server, "/_henge/topology", null);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).startsWith("application/json");
            JsonNode topology = response.json();
            assertThat(topology.at("/store/readable").asBoolean()).isTrue();
            assertThat(topology.at("/node/storeType").asText()).isEqualTo("in-process");
            List<String> ids = new ArrayList<>();
            topology.get("services").forEach(service -> {
                ids.add(service.get("id").asText());
                assertThat(service.get("state").asText()).isEqualTo("hosted");
            });
            assertThat(ids).containsExactly("counter-service@1", "counter-service@2");

            // The default consumer got the default version; the pinned one asked for version 2.
            List<String> edges = new ArrayList<>();
            topology.get("dependencies").forEach(edge -> edges.add(edge.get("from").asText() + " -> " + edge.get("to").asText()
                    + (edge.get("remote").asBoolean() ? " (remote)" : "")));
            assertThat(edges).containsExactly("bean:defaultConsumer -> counter-service@1", "bean:pinnedConsumer -> counter-service@2");
            List<String> consumers = new ArrayList<>();
            topology.get("consumers").forEach(consumer -> consumers.add(consumer.get("id").asText()));
            assertThat(consumers).containsExactly("bean:defaultConsumer", "bean:pinnedConsumer");
        }
    }

    @Test
    void thePageIsServedWithAPolicyThatLoadsNothingFromElsewhere() {
        try (var server = start("henge.topology.enabled=true")) {
            Response page = get(server, "/_henge/topology/ui", null);

            assertThat(page.status()).isEqualTo(200);
            assertThat(page.contentType()).startsWith("text/html");
            assertThat(page.body()).contains("Henge topology");
            assertThat(page.contentSecurityPolicy()).contains("default-src 'none'").contains("connect-src 'self'");
        }
    }

    @Test
    void withASecretTheJsonNeedsItAndThePageDoesNot() {
        try (var server = start("henge.topology.enabled=true", "henge.transport.secret=s3cr3t")) {
            assertThat(get(server, "/_henge/topology", null).status()).isEqualTo(403);
            assertThat(get(server, "/_henge/topology", "wrong").status()).isEqualTo(403);
            assertThat(get(server, "/_henge/topology", "s3cr3t").status()).isEqualTo(200);
            assertThat(get(server, "/_henge/topology/ui", null).status()).isEqualTo(200);
        }
    }

    @Test
    void itWorksUnderANonRootServletPath() {
        try (var server = start("henge.topology.enabled=true", "henge.transport.secret=s3cr3t", "spring.mvc.servlet.path=/api")) {
            assertThat(get(server, "/api/_henge/topology", null).status()).isEqualTo(403);
            assertThat(get(server, "/api/_henge/topology", "s3cr3t").status()).isEqualTo(200);
            assertThat(get(server, "/api/_henge/topology/ui", null).status()).isEqualTo(200);
        }
    }

    @Test
    void withNoSecretItIsOpenLikeDispatch() {
        try (var server = start("henge.topology.enabled=true")) {
            assertThat(get(server, "/_henge/topology", null).status()).isEqualTo(200);
        }
    }

    @Test
    void itIsNotServedWhenThisProcessDoesntServeHengeAtAll() {
        try (var server = start("henge.topology.enabled=true", "henge.server.enabled=false")) {
            assertThat(get(server, "/_henge/topology", null).status()).isNotEqualTo(200);
        }
    }
}
