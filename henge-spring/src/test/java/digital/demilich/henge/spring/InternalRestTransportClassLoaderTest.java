package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Remote exception types are resolved against the application's bean classloader, never the
 * loader of the interface that happens to declare the called method.
 */
class InternalRestTransportClassLoaderTest {

    /** Inherits {@code get()} from a JDK interface, whose declaring class has a {@code null} loader. */
    interface InheritingService extends Supplier<String> {
    }

    private static final String SERVICE_URL = "http://svc.test";

    private InternalRestTransport transport(RestClient.Builder builder) {
        MockEnvironment environment = new MockEnvironment().withProperty("henge.services.svc.url", SERVICE_URL);
        return new InternalRestTransport(builder.build(), HengeTransportSupport.objectMapper(), new HengeProperties(environment));
    }

    private static MockRestServiceServer respondWithBusinessException(RestClient.Builder builder, String exceptionType) {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI().toString()).isEqualTo(SERVICE_URL + "/_henge/svc/1/get"))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"x\",\"exceptionType\":\"" + exceptionType + "\",\"exceptionMessage\":\"boom\"}"));
        return server;
    }

    private static ServiceInvocation invocationOfInheritedMethod() throws Exception {
        Method inherited = InheritingService.class.getMethod("get");
        assertThat(inherited.getDeclaringClass().getClassLoader()).as("sanity: JDK interface, bootstrap loader").isNull();
        return new ServiceInvocation("svc", 1, "get", inherited, new Object[0]);
    }

    @Test
    void exceptionIsReconstructedEvenWhenTheCalledMethodIsInheritedFromAJdkInterface() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        respondWithBusinessException(builder, EchoFailureException.class.getName());

        assertThatThrownBy(() -> transport(builder).invoke(invocationOfInheritedMethod()))
                .isInstanceOf(EchoFailureException.class)
                .hasMessage("boom");
    }

    @Test
    void exceptionLookupUsesTheBeanClassLoaderItWasGiven() throws Exception {
        // A loader that can't see the exception class, standing in for a child loader the
        // contract's own (parent) loader couldn't see into: the type is only resolvable via the
        // loader the transport was given, so hiding it there must force the fallback.
        ClassLoader hidingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (EchoFailureException.class.getName().equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        RestClient.Builder builder = RestClient.builder();
        respondWithBusinessException(builder, EchoFailureException.class.getName());
        InternalRestTransport transport = transport(builder);
        transport.setBeanClassLoader(hidingLoader);

        assertThatThrownBy(() -> transport.invoke(invocationOfInheritedMethod()))
                .isInstanceOf(RemoteServiceException.class);
    }
}
