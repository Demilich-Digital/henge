package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
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
 * default chain (authenticate everything, CSRF on POST). These pin down the intended outcome:
 * internal dispatch just works, nothing else the application serves is opened up, and the
 * {@code henge.transport.secret} is enforced by Spring Security itself as an authentication.
 */
class SpringSecurityCompatibilityTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(HengeAutoConfiguration.class));

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
                    .properties("spring.main.banner-mode=off", "henge.server.enabled=false",
                            "henge.services.echo-service.mode=internal-rest",
                            "henge.services.echo-service.url=http://localhost:" + port)
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
    void remoteDispatchWorksUnderANonRootServletPath() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "spring.mvc.servlet.path=/api",
                        "henge.transport.secret=s3cr3t")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "henge.server.enabled=false",
                            "henge.transport.secret=s3cr3t",
                            "henge.services.echo-service.mode=internal-rest",
                            "henge.services.echo-service.url=http://localhost:" + port + "/api")
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
            assertThatThrownBy(() -> raw.get().uri("http://localhost:" + port + "/_henge/echo-service/1/echo").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        } finally {
            server.close();
        }
    }

    @Test
    void bootsDefaultChainIsKeptAlongsideTheHengeOne() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            // Boot's own default chain AND ours: registering ours must not have made Boot's back off.
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(2).containsKey(HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME);
        } finally {
            server.close();
        }
    }

    @Test
    void anApplicationsOwnChainCoexistsWithTheHengeOne() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class, OwnChainConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            RestClient raw = RestClient.create();

            // Boot's default stepped aside for the application's chain; ours was added next to it.
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(2).containsKeys("ownChain", HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME);
            assertThat(raw.get().uri("http://localhost:" + port + "/open").retrieve().body(String.class)).isEqualTo("anyone");
            assertThatThrownBy(() -> raw.get().uri("http://localhost:" + port + "/protected").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
            assertThat(raw.post().uri("http://localhost:" + port + "/_henge/echo-service/1/echo")
                    .contentType(MediaType.APPLICATION_JSON).body("[\"hi\"]").retrieve().body(String.class))
                    .isEqualTo("\"echo:hi\"");
        } finally {
            server.close();
        }
    }

    @Test
    void anApplicationCanReplaceTheHengeChainByDefiningABeanOfTheSameName() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class, ReplacementChainConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            assertThat(server.getBean(HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME)).isSameAs(ReplacementChainConfig.INSTANCE.get());
            assertThatThrownBy(() -> RestClient.create().post()
                            .uri("http://localhost:" + port + "/_henge/echo-service/1/echo")
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
    void noChainIsRegisteredWhenTheDispatcherIsDisabled() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "henge.server.enabled=false")
                .run();
        try {
            assertThat(server.getBeansOfType(SecurityFilterChain.class)).hasSize(1).doesNotContainKey(HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME);
        } finally {
            server.close();
        }
    }

    // ---- the secret is a real Spring Security authentication ----

    private static final String SECRET_HEADER = "Henge-Internal-Secret";

    private static int postStatus(int port, String secretHeader) {
        try {
            RestClient.RequestBodySpec request = RestClient.create().post()
                    .uri("http://localhost:" + port + "/_henge/echo-service/1/echo")
                    .contentType(MediaType.APPLICATION_JSON);
            if (secretHeader != null) {
                request.header(SECRET_HEADER, secretHeader);
            }
            request.body("[\"hi\"]").retrieve().toBodilessEntity();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void withASecretSpringSecurityRequiresItAndRejectsWrongOrMissingOnesWith403() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "henge.transport.secret=s3cr3t")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            assertThat(postStatus(port, "s3cr3t")).isEqualTo(200);
            assertThat(postStatus(port, "wrong")).isEqualTo(403);
            assertThat(postStatus(port, null)).isEqualTo(403);
            // The application's own endpoints are unaffected by the secret.
            assertThatThrownBy(() -> RestClient.create().get().uri("http://localhost:" + port + "/protected").retrieve().body(String.class))
                    .isInstanceOfSatisfying(RestClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        } finally {
            server.close();
        }
    }

    @Test
    void withoutASecretTheChainPermitsAllExplicitly() {
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();

            assertThat(postStatus(port, null)).isEqualTo(200);
            assertThat(postStatus(port, "anything")).isEqualTo(200);
        } finally {
            server.close();
        }
    }

    @Test
    void anAuthenticatedCallerIsAnAuthenticatedPrincipalWithTheHengeServiceRole() throws Exception {
        // What the service method itself sees in the SecurityContext once the chain's filters have run.
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", "henge.transport.secret=s3cr3t")
                .run();
        try {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            EchoServiceImpl.LAST_AUTHENTICATION.set(null);
            assertThat(postStatus(port, "s3cr3t")).isEqualTo(200);

            org.springframework.security.core.Authentication seen = EchoServiceImpl.LAST_AUTHENTICATION.get();
            assertThat(seen).isNotNull();
            assertThat(seen.isAuthenticated()).isTrue();
            assertThat(seen.getName()).isEqualTo(HengeSecurityAutoConfiguration.PRINCIPAL);
            assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_" + HengeSecurityAutoConfiguration.ROLE);
        } finally {
            server.close();
        }
    }

    @Test
    void starterStillLoadsWithoutSpringSecurityOnTheClasspath() {
        runner.withClassLoader(new FilteredClassLoader(HttpSecurity.class)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME);
            assertThat(ctx).hasSingleBean(HengeDispatcherController.class);
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

    /** The application wants Spring Security to govern /_henge itself. */
    @Configuration
    static class ReplacementChainConfig {

        static final java.util.concurrent.atomic.AtomicReference<SecurityFilterChain> INSTANCE = new java.util.concurrent.atomic.AtomicReference<>();

        @Bean(HengeSecurityAutoConfiguration.CHAIN_BEAN_NAME)
        @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain hengeSecurityFilterChain(HttpSecurity http) throws Exception {
            SecurityFilterChain chain = http
                    .securityMatcher("/_henge/**")
                    .authorizeHttpRequests(r -> r.anyRequest().authenticated())
                    .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                    .build();
            INSTANCE.set(chain);
            return chain;
        }
    }
}
