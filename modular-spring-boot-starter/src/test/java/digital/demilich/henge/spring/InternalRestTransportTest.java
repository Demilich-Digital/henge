package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Focused, deterministic tests for {@link InternalRestTransport} details that don't need a real
 * HTTP round trip (see {@link ModularServiceRemoteDispatchIntegrationTest} for the end-to-end
 * version) — {@code modular.remote-url-template} substitution, base-URL normalization, and that the configured
 * connect/read timeouts actually bound how long a stalled call can hang.
 */
class InternalRestTransportTest {

    @Test
    void substitutesServiceNameIntoRemoteUrlTemplate() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("modular.remote-url-template", "http://{service}.default.svc.cluster.local:8080");
        ModularProperties properties = new ModularProperties(environment);
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), ModularTransportSupport.objectMapper(), properties);

        assertThat(transport.resolveFromTemplate("audit-service", 1))
                .isEqualTo("http://audit-service.default.svc.cluster.local:8080");
    }

    @Test
    void substitutesServiceNameAndVersionIntoRemoteUrlTemplate() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("modular.remote-url-template", "http://{service}-v{version}.default.svc.cluster.local:8080");
        ModularProperties properties = new ModularProperties(environment);
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), ModularTransportSupport.objectMapper(), properties);

        assertThat(transport.resolveFromTemplate("audit-service", 2))
                .isEqualTo("http://audit-service-v2.default.svc.cluster.local:8080");
    }

    @Test
    void returnsNullWhenNoTemplateConfigured() {
        ModularProperties properties = new ModularProperties(new MockEnvironment());
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), ModularTransportSupport.objectMapper(), properties);

        assertThat(transport.resolveFromTemplate("audit-service", 1)).isNull();
    }

    @Test
    void anUnknownTemplatePlaceholderFailsAtStartup() {
        ModularProperties properties = new ModularProperties(new MockEnvironment()
                .withProperty("modular.remote-url-template", "http://{service}.{namespace}.svc:8080"));

        assertThatThrownBy(() -> new InternalRestTransport(RestClient.builder().build(), ModularTransportSupport.objectMapper(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{namespace}");
    }

    @Test
    void aMalformedUrlFailsAsARemoteServiceExceptionNamingTheConfig() throws Exception {
        ModularProperties properties = new ModularProperties(new MockEnvironment()
                .withProperty("modular.services.echo-service.url", "http://echo host:8080"));
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), ModularTransportSupport.objectMapper(), properties);
        Method echoMethod = EchoService.class.getMethod("echo", String.class);

        assertThatThrownBy(() -> transport.invoke(new ServiceInvocation("echo-service", 1, "echo", echoMethod, new Object[] {"hi"})))
                .isInstanceOf(RemoteServiceException.class)
                .hasMessageContaining("modular.services.echo-service.url");
    }

    @Test
    void trailingSlashOnTheBaseUrlDoesNotDoubleTheSeparator() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("modular.services.echo-service.url", "http://echo-host:8080/");
        ModularProperties properties = new ModularProperties(environment);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://echo-host:8080/_modular/echo-service/1/echo"))
                .andRespond(withSuccess("\"hi\"", MediaType.APPLICATION_JSON));
        InternalRestTransport transport =
                new InternalRestTransport(builder.build(), ModularTransportSupport.objectMapper(), properties);

        Method echoMethod = EchoService.class.getMethod("echo", String.class);
        Object result = transport.invoke(new ServiceInvocation("echo-service", 1, "echo", echoMethod, new Object[] {"hi"}));

        assertThat(result).isEqualTo("hi");
        server.verify();
    }

    @Test
    void readTimeoutFailsFastAgainstAStalledEndpoint() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread stallingServer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // Test teardown closing the server socket also unblocks/errors this thread.
                }
            });
            stallingServer.setDaemon(true);
            stallingServer.start();

            MockEnvironment environment = new MockEnvironment()
                    .withProperty("modular.services.echo-service.url", "http://localhost:" + serverSocket.getLocalPort())
                    .withProperty("modular.transport.read-timeout", "200");
            ModularProperties properties = new ModularProperties(environment);
            InternalRestTransport transport = new InternalRestTransport(
                    ModularTransportSupport.restClient(properties), ModularTransportSupport.objectMapper(), properties);

            Method echoMethod = EchoService.class.getMethod("echo", String.class);
            ServiceInvocation invocation =
                    new ServiceInvocation("echo-service", 1, "echo", echoMethod, new Object[] {"hi"});

            long start = System.nanoTime();
            assertThatThrownBy(() -> transport.invoke(invocation))
                    .isInstanceOf(RemoteServiceException.class)
                    .hasMessageContaining("timed out");
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMillis).isLessThan(3000);
        }
    }
}
