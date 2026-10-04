package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.HashMap;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two processes that have never been told each other's address: one hosts the echo service on real
 * HTTP and advertises it, the other calls it by finding that advertisement on the shared datastore.
 */
@Testcontainers(disabledWithoutDocker = true)
class ModularAdvertisementRoutingTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private record RunningServer(Tomcat tomcat, AnnotationConfigWebApplicationContext context, int port) {
        void stop() throws Exception {
            context.close();
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Configuration
    @EnableWebMvc
    static class WebMvcSupport {
    }

    /** Settings that put a process on the datastore, and make it advertise itself at {@code port}. */
    private static Map<String, Object> hosting(int port) {
        return Map.of("modular.advertise.url", "http://localhost:" + port);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The port has to be known before the context starts, since the process advertises it as it starts. */
    private static RunningServer startServer(Map<String, Object> properties, SystemEphemeralDatastore sharedStore, int port) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector();
        Context tomcatContext = tomcat.addContext("", null);

        AnnotationConfigWebApplicationContext serverContext = new AnnotationConfigWebApplicationContext();
        serverContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        if (sharedStore != null) {
            serverContext.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton("sharedStore", sharedStore));
        }
        serverContext.register(
                EchoTestConfig.class, ModularTransportConfiguration.class, ModularDispatcherConfiguration.class, WebMvcSupport.class);
        Wrapper wrapper = Tomcat.addServlet(tomcatContext, "dispatcher", new DispatcherServlet(serverContext));
        wrapper.setLoadOnStartup(1);
        tomcatContext.addServletMappingDecoded("/*", "dispatcher");
        tomcat.start();
        return new RunningServer(tomcat, serverContext, port);
    }

    private static AnnotationConfigApplicationContext startClient(Map<String, Object> properties, SystemEphemeralDatastore sharedStore) {
        var client = new AnnotationConfigApplicationContext();
        Map<String, Object> all = new HashMap<>(properties);
        all.put("modular.services.echo-service.mode", "internal-rest");
        client.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", all));
        if (sharedStore != null) {
            client.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton("sharedStore", sharedStore));
        }
        client.register(EchoTestConfig.class, ModularTransportConfiguration.class);
        client.refresh();
        return client;
    }

    @Test
    void aClientWithNoUrlFindsTheServerThroughItsAdvertisement() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int port = freePort();
        RunningServer server = startServer(hosting(port), shared, port);
        try (var client = startClient(Map.of(), shared)) {
            EchoService echo = client.getBean(EchoService.class);
            assertThat(Proxy.isProxyClass(echo.getClass())).isTrue();

            assertThat(echo.echo("hi")).isEqualTo("echo:hi");
            assertThat(server.context().getBean(EchoServiceImpl.class).getCallCount()).isEqualTo(1);
        } finally {
            server.stop();
        }
    }

    @Test
    void anExplicitUrlStillWinsOverAnAdvertisement() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int port = freePort();
        RunningServer server = startServer(hosting(port), shared, port);
        try (var client = startClient(Map.of("modular.services.echo-service.url", "http://localhost:1"), shared)) {
            assertThatThrownBy(() -> client.getBean(EchoService.class).echo("hi")).isInstanceOf(RemoteServiceException.class);
            assertThat(server.context().getBean(EchoServiceImpl.class).getCallCount()).isZero();
        } finally {
            server.stop();
        }
    }

    @Test
    void onceTheServerHasStoppedANewClientHasNobodyToCall() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int port = freePort();
        RunningServer server = startServer(hosting(port), shared, port);
        server.stop();

        try (var client = startClient(Map.of(), shared)) {
            assertThatThrownBy(() -> client.getBean(EchoService.class).echo("hi"))
                    .isInstanceOf(RemoteServiceException.class)
                    .hasMessageContaining("no process advertises it");
        }
    }

    @Test
    void twoProcessesOnRedisFindEachOtherWithoutAnyAddressConfigured() throws Exception {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        Map<String, Object> onRedis = Map.of("modular.store.type", "redis", "modular.store.redis.uri", uri);
        int port = freePort();
        Map<String, Object> serverProperties = new HashMap<>(onRedis);
        serverProperties.putAll(hosting(port));
        RunningServer server = startServer(serverProperties, null, port);
        try (var client = startClient(onRedis, null)) {
            assertThat(client.getBean(EchoService.class).echo("over redis")).isEqualTo("echo:over redis");
            assertThat(server.context().getBean(EchoServiceImpl.class).getCallCount()).isEqualTo(1);
        } finally {
            server.stop();
        }
    }
}
