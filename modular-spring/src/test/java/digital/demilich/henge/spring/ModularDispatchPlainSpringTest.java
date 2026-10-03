package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoNotFoundException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.Measurement;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Proves the whole point of this module end to end with zero Spring Boot involvement: one process
 * embeds {@link EchoServiceImpl} behind a hand-embedded Tomcat + {@link DispatcherServlet}
 * (exactly how Spring MVC was deployed before Spring Boot existed), the other is handed a dynamic
 * proxy that dispatches {@link EchoService} calls to the first over real HTTP — mirroring
 * {@code modular-spring-boot-starter}'s {@code ModularServiceRemoteDispatchIntegrationTest}, but
 * without a single Boot class anywhere in the call stack.
 */
class ModularDispatchPlainSpringTest {

    @Test
    void internalRestClientReachesEmbeddedServerOverHttp() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            AnnotationConfigApplicationContext clientContext = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port()));
            try {
                EchoService proxied = clientContext.getBean(EchoService.class);
                assertThat(Proxy.isProxyClass(proxied.getClass())).isTrue();
                assertThat(proxied.echo("hi")).isEqualTo("echo:hi");

                EchoServiceImpl serverImpl = server.context().getBean(EchoServiceImpl.class);
                assertThat(serverImpl.getCallCount()).isEqualTo(1);

                // ImmutableList round-trips as an argument and a return value over real HTTP,
                // proving HengeCollectionsModule is wired into both ends of the transport.
                assertThat(proxied.upperCaseAll(ImmutableList.of("a", "b"))).containsExactly("A", "B");

                // Decimals are bound from the JSON text, never via a tree's double: a BigDecimal keeps
                // its precision and scale, and -0.0 stays -0.0, in both directions.
                Measurement exact = new Measurement(new BigDecimal("12345678901234567.8901"), -0.0);
                assertThat(proxied.measure(exact)).isEqualTo(exact);
                Measurement scaled = new Measurement(new BigDecimal("1.50"), 0.1);
                assertThat(proxied.measure(scaled)).isEqualTo(scaled);
            } finally {
                clientContext.close();
            }
        } finally {
            server.stop();
        }
    }

    /**
     * The failure-transparency half of the proof: an {@code internal-rest} call whose remote
     * implementation throws a custom (non-JDK) unchecked exception surfaces client-side as that
     * exact exception type and message -- not a generic {@link RemoteServiceException} -- via
     * {@link RemoteExceptionReconstructor}, with the network-diagnostic {@link
     * RemoteServiceException} still reachable as its cause.
     */
    @Test
    void internalRestClientReconstructsRemoteExceptionType() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            AnnotationConfigApplicationContext clientContext = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port()));
            try {
                EchoService proxied = clientContext.getBean(EchoService.class);
                assertThatThrownBy(() -> proxied.explode("boom"))
                        .isInstanceOf(EchoFailureException.class)
                        .hasMessage("boom")
                        .cause()
                        .isInstanceOf(RemoteServiceException.class);
            } finally {
                clientContext.close();
            }
        } finally {
            server.stop();
        }
    }

    /**
     * When both ends agree on {@code modular.transport.secret}, dispatch works exactly as without
     * one -- the header is present and valid, so {@link ModularDispatcherController} lets the call
     * through.
     */
    @Test
    void matchingSecretIsAccepted() throws Exception {
        RunningServer server = startServer(Map.of("modular.transport.secret", "s3cr3t"));
        try {
            AnnotationConfigApplicationContext clientContext = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port(),
                    "modular.transport.secret", "s3cr3t"));
            try {
                EchoService proxied = clientContext.getBean(EchoService.class);
                assertThat(proxied.echo("hi")).isEqualTo("echo:hi");
            } finally {
                clientContext.close();
            }
        } finally {
            server.stop();
        }
    }

    /**
     * A client with no secret configured (or the wrong one) against a server that requires one
     * gets a 403, surfaced client-side as a {@link RemoteServiceException} -- proving the
     * dispatcher actually enforces {@code modular.transport.secret} rather than merely accepting
     * it when present.
     */
    @Test
    void missingOrWrongSecretIsRejected() throws Exception {
        RunningServer server = startServer(Map.of("modular.transport.secret", "s3cr3t"));
        try {
            AnnotationConfigApplicationContext noSecretClient = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port()));
            try {
                EchoService proxied = noSecretClient.getBean(EchoService.class);
                assertThatThrownBy(() -> proxied.echo("hi"))
                        .isInstanceOf(RemoteServiceException.class)
                        .cause()
                        .isInstanceOf(RestClientResponseException.class)
                        .satisfies(e -> assertThat(((RestClientResponseException) e).getStatusCode().value()).isEqualTo(403));
            } finally {
                noSecretClient.close();
            }

            AnnotationConfigApplicationContext wrongSecretClient = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port(),
                    "modular.transport.secret", "wrong"));
            try {
                EchoService proxied = wrongSecretClient.getBean(EchoService.class);
                assertThatThrownBy(() -> proxied.echo("hi")).isInstanceOf(RemoteServiceException.class);
            } finally {
                wrongSecretClient.close();
            }
        } finally {
            server.stop();
        }
    }

    /**
     * The secret is checked before the request body is touched: an unauthenticated caller sending
     * malformed JSON gets 403, not a 400 that proves the endpoint parsed their input. With the
     * right secret the same body is a 400.
     */
    /**
     * An unannotated business exception is a 500 (this framework's "unclassified failure" status);
     * one annotated {@code @ErrorStatus(404)} is answered with that status instead, still carrying
     * its type so the client reconstructs it; an out-of-range annotation value falls back to 500.
     */
    @Test
    void businessExceptionStatusComesFromErrorStatusAnnotation() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            assertThat(explodeStatusAndBody(server, "boom"))
                    .satisfies(r -> {
                        assertThat(r.status()).isEqualTo(500);
                        assertThat(r.body()).contains("EchoFailureException");
                    });
            assertThat(explodeStatusAndBody(server, "not-found"))
                    .satisfies(r -> {
                        assertThat(r.status()).isEqualTo(404);
                        assertThat(r.body()).contains("EchoNotFoundException");
                    });
            assertThat(explodeStatusAndBody(server, "bad-status").status()).isEqualTo(500);

            AnnotationConfigApplicationContext clientContext = startClient(Map.of(
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port()));
            try {
                EchoService proxied = clientContext.getBean(EchoService.class);
                assertThatThrownBy(() -> proxied.explode("not-found"))
                        .isInstanceOf(EchoNotFoundException.class)
                        .hasMessage("not-found");
            } finally {
                clientContext.close();
            }
        } finally {
            server.stop();
        }
    }

    private record StatusAndBody(int status, String body) {
    }

    private static StatusAndBody explodeStatusAndBody(RunningServer server, String reason) {
        try {
            RestClient.create()
                    .post()
                    .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/explode")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[\"" + reason + "\"]")
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected an error response");
        } catch (RestClientResponseException e) {
            return new StatusAndBody(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    /**
     * The prefix placeholder on {@link ModularDispatcherController}'s mapping resolves in plain
     * Spring with no {@code PropertySourcesPlaceholderConfigurer} in the context.
     */
    @Test
    void customPathPrefixIsHonoredInPlainSpring() throws Exception {
        RunningServer server = startServer(Map.of("modular.server.path-prefix", "/rpc"));
        try {
            AnnotationConfigApplicationContext clientContext = startClient(Map.of(
                    "modular.server.path-prefix", "/rpc",
                    "modular.services.echo-service.mode", "internal-rest",
                    "modular.services.echo-service.url", "http://localhost:" + server.port()));
            try {
                assertThat(clientContext.getBean(EchoService.class).echo("hi")).isEqualTo("echo:hi");
            } finally {
                clientContext.close();
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void secretIsCheckedBeforeTheBodyIsParsed() throws Exception {
        RunningServer server = startServer(Map.of("modular.transport.secret", "s3cr3t"));
        try {
            String uri = "http://localhost:" + server.port() + "/_modular/echo-service/1/echo";
            assertThatThrownBy(() -> RestClient.create()
                            .post()
                            .uri(uri)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{ not json")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));

            assertThatThrownBy(() -> RestClient.create()
                            .post()
                            .uri(uri)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(ModularDispatcherController.SECRET_HEADER, "s3cr3t")
                            .body("{ not json")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(400);
                        assertThat(e.getResponseBodyAsString()).contains("not valid JSON");
                    });
        } finally {
            server.stop();
        }
    }

    @Test
    void nonArrayRequestBodyIsRejectedNotBoundAsNull() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            assertThatThrownBy(() -> RestClient.create()
                            .post()
                            .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"value\":\"hi\"}")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(400);
                        assertThat(e.getResponseBodyAsString()).contains("JSON array");
                    });
            assertThat(server.context().getBean(EchoServiceImpl.class).getCallCount()).isZero();
        } finally {
            server.stop();
        }
    }

    @Test
    void nullElementInAnImmutableCollectionArgumentIsA400() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            assertThatThrownBy(() -> RestClient.create()
                            .post()
                            .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/upperCaseAll")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("[[\"a\", null]]")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(400);
                        assertThat(e.getResponseBodyAsString()).contains("Failed to bind argument 0");
                    });
        } finally {
            server.stop();
        }
    }

    private record RunningServer(Tomcat tomcat, AnnotationConfigWebApplicationContext context, int port) {
        void stop() throws Exception {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static RunningServer startServer(Map<String, Object> properties) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.getConnector();
        Context tomcatContext = tomcat.addContext("", null);

        AnnotationConfigWebApplicationContext serverContext = new AnnotationConfigWebApplicationContext();
        if (!properties.isEmpty()) {
            serverContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        }
        serverContext.register(
                EchoTestConfig.class, ModularTransportConfiguration.class, ModularDispatcherConfiguration.class, WebMvcSupport.class);
        DispatcherServlet dispatcherServlet = new DispatcherServlet(serverContext);
        Wrapper wrapper = Tomcat.addServlet(tomcatContext, "dispatcher", dispatcherServlet);
        wrapper.setLoadOnStartup(1);
        tomcatContext.addServletMappingDecoded("/*", "dispatcher");

        tomcat.start();
        return new RunningServer(tomcat, serverContext, tomcat.getConnector().getLocalPort());
    }

    private static AnnotationConfigApplicationContext startClient(Map<String, Object> properties) {
        AnnotationConfigApplicationContext clientContext = new AnnotationConfigApplicationContext();
        clientContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        clientContext.register(EchoTestConfig.class, ModularTransportConfiguration.class);
        clientContext.refresh();
        return clientContext;
    }

    /**
     * Plain Spring MVC needs this explicitly to register its standard {@code HttpMessageConverter}s
     * (including the Jackson-based JSON one {@link ModularDispatcherController} relies on) — Boot
     * users get this for free via {@code WebMvcAutoConfiguration}.
     */
    @Configuration
    @EnableWebMvc
    static class WebMvcSupport {
    }
}
