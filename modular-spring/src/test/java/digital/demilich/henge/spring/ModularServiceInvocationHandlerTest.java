package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class ModularServiceInvocationHandlerTest {

    interface BaseService {
        String base(String value);
    }

    interface ExtendedService extends BaseService {
        String extended(String value);
    }

    /**
     * A {@code @ModularService} interface that extends another interface is unusual but not
     * forbidden -- when it happens, a call to a method inherited from the parent interface hands
     * {@link java.lang.reflect.InvocationHandler#invoke} a {@link java.lang.reflect.Method} whose
     * {@code getDeclaringClass()} is the *parent* interface, not the full service interface the
     * proxy was created for. {@link ModularServiceInvocationHandler} must still report the full
     * service interface on the {@link ServiceInvocation} it builds -- that's what
     * {@code InternalRestTransport} fingerprints against, and it must match whatever the
     * dispatching side registered ({@code ModularServiceRegistrar} always uses the full,
     * registered interface, never a method's individual declaring class).
     */
    @Test
    void reportsTheFullServiceInterfaceEvenForAnInheritedMethod() {
        ServiceInvocation[] captured = new ServiceInvocation[1];
        ServiceTransport capturingTransport = invocation -> {
            captured[0] = invocation;
            return "ok";
        };

        ExtendedService proxy = (ExtendedService) Proxy.newProxyInstance(
                ExtendedService.class.getClassLoader(),
                new Class<?>[] {ExtendedService.class},
                new ModularServiceInvocationHandler("extended-service", "1", ExtendedService.class, capturingTransport));

        proxy.base("hi");

        assertThat(captured[0].serviceInterface()).isEqualTo(ExtendedService.class);
        assertThat(captured[0].method().getDeclaringClass())
                .as("sanity check: the JDK proxy really does hand back the parent interface as the method's declaring class")
                .isEqualTo(BaseService.class);
    }
}
