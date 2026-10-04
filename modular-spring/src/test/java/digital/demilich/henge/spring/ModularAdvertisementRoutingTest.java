package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceNotServedException;
import digital.demilich.henge.core.ServiceUnreachableException;
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

    /** Advertises {@code url} for echo-service@1 as another host, ordered after the real server's own "host". */
    private static void advertiseElsewhere(SystemEphemeralDatastore store, String url) {
        store.put("adv:echo-service@1", "zz-other", new ServiceAdvertisement(url).encode(), java.time.Duration.ofMinutes(5));
    }

    private static String deadUrl() throws Exception {
        return "http://localhost:" + freePort();
    }

    @Test
    void aCallThatCantConnectIsRetriedOnTheNextAdvertisedHost() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int port = freePort();
        RunningServer server = startServer(hosting(port), shared, port);
        advertiseElsewhere(shared, deadUrl());
        try (var client = startClient(Map.of("modular.transport.retry.backoff", "0"), shared)) {
            EchoService echo = client.getBean(EchoService.class);

            for (int i = 0; i < 6; i++) {
                assertThat(echo.echo("n" + i)).isEqualTo("echo:n" + i);
            }
            assertThat(server.context().getBean(EchoServiceImpl.class).getCallCount()).isEqualTo(6);
        } finally {
            server.stop();
        }
    }

    @Test
    void withRetriesOffTheDeadHostFailsTheCallThatPicksIt() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int port = freePort();
        RunningServer server = startServer(hosting(port), shared, port);
        advertiseElsewhere(shared, deadUrl());
        try (var client = startClient(Map.of("modular.transport.retry.max-attempts", "1"), shared)) {
            EchoService echo = client.getBean(EchoService.class);

            int failures = 0;
            for (int i = 0; i < 4; i++) {
                try {
                    echo.echo("n" + i);
                } catch (RemoteServiceException e) {
                    failures++;
                }
            }
            // Rotation reaches the dead host once; it is then skipped until the next refresh.
            assertThat(failures).isEqualTo(1);
        } finally {
            server.stop();
        }
    }

    @Test
    void aHostThatNoLongerServesTheServiceIsSkippedToo() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int livePort = freePort();
        RunningServer live = startServer(hosting(livePort), shared, livePort);
        // Reachable and answering, but it doesn't host echo-service: a "not served here" reply.
        int emptyPort = freePort();
        RunningServer empty = startServer(Map.of("modular.services.echo-service.mode", "internal-rest",
                "modular.services.echo-service.url", "http://localhost:1"), shared, emptyPort);
        advertiseElsewhere(shared, "http://localhost:" + emptyPort);
        try (var client = startClient(Map.of("modular.transport.retry.backoff", "0"), shared)) {
            EchoService echo = client.getBean(EchoService.class);

            for (int i = 0; i < 4; i++) {
                assertThat(echo.echo("n" + i)).isEqualTo("echo:n" + i);
            }
        } finally {
            empty.stop();
            live.stop();
        }
    }

    @Test
    void notServedIsntRetriedWhenThePolicyOnlyCoversConnectFailures() throws Exception {
        var shared = new InProcessEphemeralDatastore();
        int livePort = freePort();
        RunningServer live = startServer(hosting(livePort), shared, livePort);
        int emptyPort = freePort();
        RunningServer empty = startServer(Map.of("modular.services.echo-service.mode", "internal-rest",
                "modular.services.echo-service.url", "http://localhost:1"), shared, emptyPort);
        advertiseElsewhere(shared, "http://localhost:" + emptyPort);
        try (var client = startClient(Map.of("modular.transport.retry.on", "connect"), shared)) {
            EchoService echo = client.getBean(EchoService.class);

            int failures = 0;
            for (int i = 0; i < 4; i++) {
                try {
                    echo.echo("n" + i);
                } catch (ServiceNotServedException e) {
                    assertThat(e).hasMessageContaining("does not host");
                    // Rebuilt from the remote's reply, with the transport's own diagnosis chained in.
                    assertThat(e).hasCauseInstanceOf(RemoteServiceException.class);
                    failures++;
                }
            }
            assertThat(failures).isEqualTo(1);
        } finally {
            empty.stop();
            live.stop();
        }
    }

    @Test
    void aConfiguredUrlThatCantBeReachedIsRetriedThenGivenUpOn() throws Exception {
        try (var client = startClient(Map.of("modular.services.echo-service.url", deadUrl(),
                "modular.transport.retry.backoff", "0"), null)) {
            assertThatThrownBy(() -> client.getBean(EchoService.class).echo("hi"))
                    .isInstanceOf(ServiceUnreachableException.class)
                    .hasMessageContaining("gave up after 3 attempts");
        }
    }

    @Test
    void aConfiguredUrlIsNotRetriedOnNotServedBecauseItWouldSayTheSameAgain() throws Exception {
        int emptyPort = freePort();
        RunningServer empty = startServer(Map.of("modular.services.echo-service.mode", "internal-rest",
                "modular.services.echo-service.url", "http://localhost:1"), null, emptyPort);
        try (var client = startClient(Map.of("modular.services.echo-service.url", "http://localhost:" + emptyPort), null)) {
            assertThatThrownBy(() -> client.getBean(EchoService.class).echo("hi"))
                    .isInstanceOf(ServiceNotServedException.class)
                    .hasMessageContaining("does not host")
                    .hasMessageNotContaining("gave up");
        } finally {
            empty.stop();
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
