package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Proves the whole point of the framework end to end: the exact same {@code EchoTestApp} class,
 * started twice, plays two different roles purely based on config —
 * one process embeds {@link EchoServiceImpl} and serves it over {@code /_modular/**}, the other
 * is handed a dynamic proxy that dispatches {@link EchoService} calls to the first over real HTTP.
 */
class ModularServiceRemoteDispatchIntegrationTest {

    @Test
    void internalRestClientReachesEmbeddedServerOverHttp() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "modular.server.enabled=false",
                            "modular.services.echo-service.mode=internal-rest",
                            "modular.services.echo-service.url=http://localhost:" + serverPort)
                    .run();
            try {
                EchoService proxied = client.getBean(EchoService.class);
                assertThat(Proxy.isProxyClass(proxied.getClass())).isTrue();
                assertThat(proxied.echo("hi")).isEqualTo("echo:hi");

                EchoServiceImpl serverImpl = server.getBean(EchoServiceImpl.class);
                assertThat(serverImpl.getCallCount()).isEqualTo(1);
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void remoteUrlTemplateFillsInUrlWhenNoneIsExplicitlyConfigured() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "modular.server.enabled=false",
                            "modular.services.echo-service.mode=internal-rest",
                            // No modular.services.echo-service.url at all -- resolved via the
                            // template fallback instead (this fixture has only one service, so a
                            // literal template with no {service} placeholder is a legitimate,
                            // realistic usage here; substitution itself is covered by
                            // InternalRestTransportTest).
                            "modular.remote-url-template=http://localhost:" + serverPort)
                    .run();
            try {
                EchoService proxied = client.getBean(EchoService.class);
                assertThat(proxied.echo("via-template")).isEqualTo("echo:via-template");
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void matchingTransportSecretIsAccepted() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "modular.transport.secret=s3cr3t")
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "modular.server.enabled=false",
                            "modular.services.echo-service.mode=internal-rest",
                            "modular.services.echo-service.url=http://localhost:" + serverPort,
                            "modular.transport.secret=s3cr3t")
                    .run();
            try {
                EchoService proxied = client.getBean(EchoService.class);
                assertThat(proxied.echo("hi")).isEqualTo("echo:hi");
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }

    /**
     * A client with no {@code modular.transport.secret} configured against a server that requires
     * one gets rejected with a 403, proving the dispatcher actually enforces the secret rather
     * than merely accepting it when present.
     */
    @Test
    void missingTransportSecretIsRejected() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "modular.transport.secret=s3cr3t")
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "modular.server.enabled=false",
                            "modular.services.echo-service.mode=internal-rest",
                            "modular.services.echo-service.url=http://localhost:" + serverPort)
                    .run();
            try {
                EchoService proxied = client.getBean(EchoService.class);
                assertThatThrownBy(() -> proxied.echo("hi"))
                        .isInstanceOf(RemoteServiceException.class)
                        .cause()
                        .isInstanceOf(RestClientResponseException.class)
                        .satisfies(e -> assertThat(((RestClientResponseException) e).getStatusCode().value()).isEqualTo(403));
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }

    /**
     * Same as {@code modular-spring}'s {@code ModularDispatchPlainSpringTest.mismatchedContractFingerprintIsRejected}
     * -- the in-process client/server here share the exact same {@code EchoService} {@code Class}
     * object, so simulating two different <em>builds</em> of the interface disagreeing needs a raw
     * HTTP call that bypasses {@code InternalRestTransport} entirely.
     */
    @Test
    void mismatchedContractFingerprintIsRejected() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            RestClient rawClient = RestClient.create();

            assertThatThrownBy(() -> rawClient.post()
                            .uri("http://localhost:" + serverPort + "/_modular/echo-service/1/echo")
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
            server.close();
        }
    }
}
