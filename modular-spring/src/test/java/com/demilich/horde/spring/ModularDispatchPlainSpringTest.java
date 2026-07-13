package com.demilich.horde.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.demilich.horde.core.RemoteServiceException;
import com.demilich.horde.spring.fixture.echo.EchoFailureException;
import com.demilich.horde.spring.fixture.echo.EchoService;
import com.demilich.horde.spring.fixture.echo.EchoServiceImpl;
import com.demilich.horde.spring.fixture.echo.EchoTestConfig;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
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
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);

        AnnotationConfigWebApplicationContext serverContext = new AnnotationConfigWebApplicationContext();
        serverContext.register(
                EchoTestConfig.class, ModularTransportConfiguration.class, ModularDispatcherConfiguration.class, WebMvcSupport.class);
        DispatcherServlet dispatcherServlet = new DispatcherServlet(serverContext);
        Wrapper wrapper = Tomcat.addServlet(context, "dispatcher", dispatcherServlet);
        wrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/*", "dispatcher");

        tomcat.start();
        try {
            int serverPort = tomcat.getConnector().getLocalPort();

            AnnotationConfigApplicationContext clientContext = new AnnotationConfigApplicationContext();
            clientContext
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test",
                            Map.of(
                                    "modular.services.echo-service.mode", "internal-rest",
                                    "modular.services.echo-service.url", "http://localhost:" + serverPort)));
            clientContext.register(EchoTestConfig.class, ModularTransportConfiguration.class);
            clientContext.refresh();
            try {
                EchoService proxied = clientContext.getBean(EchoService.class);
                assertThat(Proxy.isProxyClass(proxied.getClass())).isTrue();
                assertThat(proxied.echo("hi")).isEqualTo("echo:hi");

                EchoServiceImpl serverImpl = serverContext.getBean(EchoServiceImpl.class);
                assertThat(serverImpl.getCallCount()).isEqualTo(1);
            } finally {
                clientContext.close();
            }
        } finally {
            tomcat.stop();
            tomcat.destroy();
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
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);

        AnnotationConfigWebApplicationContext serverContext = new AnnotationConfigWebApplicationContext();
        serverContext.register(
                EchoTestConfig.class, ModularTransportConfiguration.class, ModularDispatcherConfiguration.class, WebMvcSupport.class);
        DispatcherServlet dispatcherServlet = new DispatcherServlet(serverContext);
        Wrapper wrapper = Tomcat.addServlet(context, "dispatcher", dispatcherServlet);
        wrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/*", "dispatcher");

        tomcat.start();
        try {
            int serverPort = tomcat.getConnector().getLocalPort();

            AnnotationConfigApplicationContext clientContext = new AnnotationConfigApplicationContext();
            clientContext
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "test",
                            Map.of(
                                    "modular.services.echo-service.mode", "internal-rest",
                                    "modular.services.echo-service.url", "http://localhost:" + serverPort)));
            clientContext.register(EchoTestConfig.class, ModularTransportConfiguration.class);
            clientContext.refresh();
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
            tomcat.stop();
            tomcat.destroy();
        }
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
