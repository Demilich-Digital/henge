package digital.demilich.henge.spring;

import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * One {@code service@version} as this process sees it, and what every injection point of it talks to
 * (through a proxy, see {@link HengeServiceInvocationHandler}): the same thing whether the
 * implementation is here, was refused its lease and is reached remotely, or is configured remote.
 * Callers hold the interface, never the implementation.
 *
 * <p>A call from a caller goes through the {@link ServiceCallInterceptor}s to the target
 * ({@link #call}). A call that arrived over {@code /_henge} is handed to the implementation directly
 * ({@link #invokeLocal}): the node that made it already ran the interceptors.
 *
 * <p>See "The service binding" in {@code docs/design/self-orchestration.md}.
 */
final class ServiceBinding {

    /** Where a call ends up. */
    private interface Target {

        HengeMode mode();

        Object invoke(ServiceInvocation invocation) throws Throwable;
    }

    private record Local(Object implementation) implements Target {

        @Override
        public HengeMode mode() {
            return HengeMode.EMBEDDED;
        }

        @Override
        public Object invoke(ServiceInvocation invocation) throws Throwable {
            Method method = invocation.method();
            // The proxy's own Method object, which can belong to a non-public interface (legal, and
            // callable directly): without this it can't be invoked reflectively from here.
            method.trySetAccessible();
            try {
                return method.invoke(implementation, invocation.args());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private record Remote(String serviceName, ServiceTransport transport) implements Target {

        @Override
        public HengeMode mode() {
            return HengeMode.INTERNAL_REST;
        }

        @Override
        public Object invoke(ServiceInvocation invocation) {
            try {
                return transport.invoke(invocation);
            } catch (RuntimeException e) {
                // Passes through unchanged -- not just RemoteServiceException itself, but also
                // whatever original exception type RemoteExceptionReconstructor managed to
                // reconstruct from the remote failure. Only checked exceptions from a transport
                // still get wrapped below, since those can't be reconstructed reliably anyway (see
                // RemoteExceptionReconstructor's Javadoc).
                throw e;
            } catch (Exception e) {
                throw new RemoteServiceException(
                        "Failed to invoke Henge service '" + serviceName + "#" + invocation.methodName() + "'", e);
            }
        }
    }

    private final String serviceName;
    private final int serviceVersion;
    private final Target target;
    private final Supplier<List<ServiceCallInterceptor>> interceptorSource;
    private volatile List<ServiceCallInterceptor> interceptors;

    private ServiceBinding(String serviceName, int serviceVersion, Target target,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.target = target;
        this.interceptorSource = interceptorSource;
    }

    /**
     * @param interceptorSource asked for the interceptors on the first call, not before: the beans that
     *     are interceptors needn't exist yet when the services are being created
     */
    static ServiceBinding local(String serviceName, int serviceVersion, Object implementation,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        return new ServiceBinding(serviceName, serviceVersion, new Local(implementation), interceptorSource);
    }

    static ServiceBinding remote(String serviceName, int serviceVersion, ServiceTransport transport,
            Supplier<List<ServiceCallInterceptor>> interceptorSource) {
        return new ServiceBinding(serviceName, serviceVersion, new Remote(serviceName, transport), interceptorSource);
    }

    String serviceName() {
        return serviceName;
    }

    int serviceVersion() {
        return serviceVersion;
    }

    /** Whether this process hosts the implementation: what {@code /_henge} may serve and advertise. */
    boolean isLocal() {
        return target instanceof Local;
    }

    /** A call from a caller in this process: through the interceptors, then to the target. */
    Object call(ServiceInvocation invocation) throws Throwable {
        return new Step(invocation, interceptors(), 0).proceed();
    }

    /**
     * A call that arrived over {@code /_henge}: straight to the implementation, with the same
     * exceptions {@link Method#invoke} throws.
     */
    Object invokeLocal(Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        if (!(target instanceof Local local)) {
            throw new IllegalStateException("Henge service '" + serviceName + "' version " + serviceVersion
                    + " is not hosted by this process");
        }
        return method.invoke(local.implementation(), args);
    }

    private List<ServiceCallInterceptor> interceptors() {
        List<ServiceCallInterceptor> current = interceptors;
        if (current == null) {
            current = List.copyOf(interceptorSource.get());
            interceptors = current;
        }
        return current;
    }

    /** The call from interceptor {@code index} on. Immutable, so an interceptor may {@code proceed} again to retry. */
    private final class Step implements ServiceCallInterceptor.Chain {

        private final ServiceInvocation invocation;
        private final List<ServiceCallInterceptor> chain;
        private final int index;

        Step(ServiceInvocation invocation, List<ServiceCallInterceptor> chain, int index) {
            this.invocation = invocation;
            this.chain = chain;
            this.index = index;
        }

        @Override
        public Object proceed() throws Throwable {
            if (index < chain.size()) {
                return chain.get(index).intercept(invocation, new Step(invocation, chain, index + 1));
            }
            return target.invoke(invocation);
        }

        @Override
        public HengeMode mode() {
            return target.mode();
        }
    }
}
