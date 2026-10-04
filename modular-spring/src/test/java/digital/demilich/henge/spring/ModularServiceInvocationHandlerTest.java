package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import java.lang.reflect.Proxy;
import java.util.List;
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
     * forbidden -- a call to a method inherited from the parent hands the handler a {@link
     * java.lang.reflect.Method} whose {@code getDeclaringClass()} is the parent interface. The
     * invocation must still carry the service's own name/version and the method's RPC name.
     */
    @Test
    void inheritedMethodStillReportsTheServiceIdentity() {
        ServiceInvocation[] captured = new ServiceInvocation[1];
        ServiceTransport capturingTransport = invocation -> {
            captured[0] = invocation;
            return "ok";
        };

        ExtendedService proxy = (ExtendedService) Proxy.newProxyInstance(
                ExtendedService.class.getClassLoader(),
                new Class<?>[] {ExtendedService.class},
                new ModularServiceInvocationHandler(ServiceBinding.remote("extended-service", 1, capturingTransport, List::of)));

        proxy.base("hi");

        assertThat(captured[0].serviceName()).isEqualTo("extended-service");
        assertThat(captured[0].serviceVersion()).isEqualTo(1);
        assertThat(captured[0].methodName()).isEqualTo("base");
        assertThat(captured[0].method().getDeclaringClass()).isEqualTo(BaseService.class);
    }
}
