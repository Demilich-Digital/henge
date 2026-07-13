package com.demilich.horde.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.demilich.horde.spring.fixture.echo.EchoService;
import com.demilich.horde.spring.fixture.echo.EchoServiceImpl;
import com.demilich.horde.spring.fixture.echo.EchoTestApp;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

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
}
