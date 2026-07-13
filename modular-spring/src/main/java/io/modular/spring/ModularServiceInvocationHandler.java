package io.modular.spring;

import io.modular.core.RemoteServiceException;
import io.modular.core.ServiceInvocation;
import io.modular.core.ServiceMethod;
import io.modular.core.ServiceTransport;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

class ModularServiceInvocationHandler implements InvocationHandler {

    private final String serviceName;
    private final String serviceVersion;
    private final ServiceTransport transport;

    ModularServiceInvocationHandler(String serviceName, String serviceVersion, ServiceTransport transport) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.transport = transport;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "ModularServiceProxy[" + serviceName + "/" + serviceVersion + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        String methodName = rpcName(method);
        Object[] callArgs = args == null ? new Object[0] : args;
        ServiceInvocation invocation = new ServiceInvocation(serviceName, serviceVersion, methodName, method, callArgs);

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
                    "Failed to invoke modular service '" + serviceName + "#" + methodName + "'", e);
        }
    }

    private static String rpcName(Method method) {
        ServiceMethod override = method.getAnnotation(ServiceMethod.class);
        return (override != null && !override.name().isBlank()) ? override.name() : method.getName();
    }
}
