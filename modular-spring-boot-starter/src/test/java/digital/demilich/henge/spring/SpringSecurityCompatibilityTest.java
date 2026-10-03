package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Spring Security is on this module's test classpath, so every Boot test here already runs with its
 * default chain (authenticate everything, CSRF on POST); {@code src/test/resources/application.properties}
 * opts in to {@code modular.server.permit-spring-security}. These pin down the intended outcome:
 * with the opt-in, internal dispatch works and nothing else the application serves is opened up;
 * without it, Spring Security is left exactly as the application configured it.
 */
class SpringSecurityCompatibilityTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModularAutoConfiguration.class));

    @Test
    void remoteDispatchWorksThroughSpringSecuritysDefaultChain() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "modular.server.enabled=false",
                            "modular.services.echo-service.mode=internal-rest",
                            "modular.services.echo-service.url=http://localhost:" + port)
                    .run();
            try {
                assertThat(client.getBean(EchoService.class).echo("hi")).isEqualTo("echo:hi");
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void theApplicationsOwnEndpointsStayProtected() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            RestClient raw = RestClient.create();

            assertThatThrownBy(() -> raw.get().uri("http://localhost:" + port + "/protected").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));

            // Only POST under the prefix is skipped: a GET there is still the application's concern.
            assertThatThrownBy(() -> raw.get().uri("http://localhost:" + port + "/_modular/echo-service/1/echo").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        } finally {
            server.close();
        }
    }

    @Test
    void springSecurityIsLeftAloneUnlessThePropertyOptsIn() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                // a command-line arg outranks application.properties, which opts in for the other tests
                .run("--modular.server.permit-spring-security=false");
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            assertThat(server.getBeansOfType(WebSecurityCustomizer.class)).isEmpty();
            assertThatThrownBy(() -> RestClient.create().post()
                            .uri("http://localhost:" + port + "/_modular/echo-service/1/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("[\"hi\"]")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        } finally {
            server.close();
        }
    }

    @Test
    void securityCustomizerIsRegisteredOnlyWhenOptedInForServletAppsWithSpringSecurity() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(WebSecurityCustomizer.class));
        runner.withPropertyValues("modular.server.permit-spring-security=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebSecurityCustomizer.class));
    }

    @Test
    void starterStillLoadsWithoutSpringSecurityOnTheClasspath() {
        runner.withPropertyValues("modular.server.permit-spring-security=true")
                .withClassLoader(new FilteredClassLoader(WebSecurityCustomizer.class)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(WebSecurityCustomizer.class);
            assertThat(ctx).hasSingleBean(ModularDispatcherController.class);
        });
    }
}
