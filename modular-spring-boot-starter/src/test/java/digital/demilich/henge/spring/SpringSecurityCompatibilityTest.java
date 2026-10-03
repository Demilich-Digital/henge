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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
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

            assertThat(server.containsBean(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME)).isFalse();
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
    void bootsDefaultChainIsKeptAlongsideTheModularOne() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            // Boot's own default chain AND ours: registering ours must not have made Boot's back off.
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(2).containsKey(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME);
        } finally {
            server.close();
        }
    }

    @Test
    void anApplicationsOwnChainCoexistsWithTheModularOne() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class, OwnChainConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            RestClient raw = RestClient.create();

            // Boot's default stepped aside for the application's chain; ours was added next to it.
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(2).containsKeys("ownChain", ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME);
            assertThat(raw.get().uri("http://localhost:" + port + "/open").retrieve().body(String.class)).isEqualTo("anyone");
            assertThatThrownBy(() -> raw.get().uri("http://localhost:" + port + "/protected").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
            assertThat(raw.post().uri("http://localhost:" + port + "/_modular/echo-service/1/echo")
                    .contentType(MediaType.APPLICATION_JSON).body("[\"hi\"]").retrieve().body(String.class))
                    .isEqualTo("\"echo:hi\"");
        } finally {
            server.close();
        }
    }

    @Test
    void anApplicationCanReplaceTheModularChainByDefiningABeanOfTheSameName() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class, ReplacementChainConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            assertThat(server.getBean(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME)).isSameAs(ReplacementChainConfig.INSTANCE.get());
            assertThatThrownBy(() -> RestClient.create().post()
                            .uri("http://localhost:" + port + "/_modular/echo-service/1/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("[\"hi\"]")
                            .retrieve()
                            .toBodilessEntity())
                    .as("the application's own chain governs the path now: its default CSRF protection (403) or its "
                            + "authentication requirement (401) rejects the call; ours would have let it through")
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isIn(401, 403));
        } finally {
            server.close();
        }
    }

    @Test
    void chainIsOnlyRegisteredWhenOptedIn() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run("--modular.server.permit-spring-security=false");
        try {
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(1).doesNotContainKey(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME);
        } finally {
            server.close();
        }
    }

    @Test
    void starterStillLoadsWithoutSpringSecurityOnTheClasspath() {
        runner.withPropertyValues("modular.server.permit-spring-security=true")
                .withClassLoader(new FilteredClassLoader(HttpSecurity.class)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME);
            assertThat(ctx).hasSingleBean(ModularDispatcherController.class);
        });
    }

    /** The application took over Spring Security configuration with a chain of its own. */
    @Configuration
    static class OwnChainConfig {

        @Bean
        SecurityFilterChain ownChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(r -> r.requestMatchers("/open").permitAll().anyRequest().authenticated())
                    .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                    .build();
        }
    }

    /** The application wants Spring Security to govern /_modular itself. */
    @Configuration
    static class ReplacementChainConfig {

        static final java.util.concurrent.atomic.AtomicReference<SecurityFilterChain> INSTANCE = new java.util.concurrent.atomic.AtomicReference<>();

        @Bean(ModularSecurityAutoConfiguration.CHAIN_BEAN_NAME)
        @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain modularSecurityFilterChain(HttpSecurity http) throws Exception {
            SecurityFilterChain chain = http
                    .securityMatcher("/_modular/**")
                    .authorizeHttpRequests(r -> r.anyRequest().authenticated())
                    .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                    .build();
            INSTANCE.set(chain);
            return chain;
        }
    }
}
