package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceInvocation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** The proxy at every injection point of a {@code @ModularService}: hands each call to its {@link ServiceBinding}. */
class ModularServiceInvocationHandler implements InvocationHandler {

    private final ServiceBinding binding;

    ModularServiceInvocationHandler(ServiceBinding binding) {
        this.binding = binding;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "ModularServiceProxy[" + binding.serviceName() + "/" + binding.serviceVersion() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        String methodName = ModularServiceDescriptor.rpcName(method);
        Object[] callArgs = args == null ? new Object[0] : args;
        return binding.call(new ServiceInvocation(binding.serviceName(), binding.serviceVersion(), methodName, method, callArgs));
    }
}
