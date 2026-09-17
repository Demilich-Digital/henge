package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.lang.reflect.Proxy;
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
     * The in-process client/server pair in these tests share the exact same {@code EchoService}
     * {@code Class} object, so {@link InternalRestTransport} always sends a matching fingerprint
     * -- there's no way to simulate two different <em>builds</em> of the interface without a raw
     * HTTP call that bypasses the transport entirely, sending a fingerprint that doesn't match
     * whatever the dispatcher actually computed.
     */
    @Test
    void mismatchedContractFingerprintIsRejected() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            RestClient rawClient = RestClient.create();
            assertThatThrownBy(() -> rawClient.post()
                            .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(ModularDispatcherController.FINGERPRINT_HEADER, "not-the-real-fingerprint")
                            .body("[\"hi\"]")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOf(RestClientResponseException.class)
                    .satisfies(e -> {
                        RestClientResponseException responseException = (RestClientResponseException) e;
                        assertThat(responseException.getStatusCode().value()).isEqualTo(409);
                        assertThat(responseException.getResponseBodyAsString())
                                .contains("client=not-the-real-fingerprint")
                                .contains("server=");
                    });
        } finally {
            server.stop();
        }
    }

    /**
     * A request with no fingerprint header at all (an older client, or one with
     * {@code modular.transport.verify-contract=false}) is accepted -- this check exists to catch
     * an actively wrong fingerprint, not to require every caller to participate.
     */
    @Test
    void missingContractFingerprintHeaderIsAccepted() throws Exception {
        RunningServer server = startServer(Map.of());
        try {
            String response = RestClient.create()
                    .post()
                    .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("[\"hi\"]")
                    .retrieve()
                    .body(String.class);
            assertThat(response).isEqualTo("\"echo:hi\"");
        } finally {
            server.stop();
        }
    }

    /** {@code verify-contract=false} on the dispatcher ignores a fingerprint header even if present. */
    @Test
    void verifyContractDisabledOnServerIgnoresMismatchedFingerprint() throws Exception {
        RunningServer server = startServer(Map.of("modular.transport.verify-contract", "false"));
        try {
            String response = RestClient.create()
                    .post()
                    .uri("http://localhost:" + server.port() + "/_modular/echo-service/1/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(ModularDispatcherController.FINGERPRINT_HEADER, "not-the-real-fingerprint")
                    .body("[\"hi\"]")
                    .retrieve()
                    .body(String.class);
            assertThat(response).isEqualTo("\"echo:hi\"");
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
