package digital.demilich.henge.core;

import java.lang.reflect.Method;

/**
 * Everything a {@link ServiceTransport} needs to carry out one remote call:
 * which service/version/method is being invoked, the reflected {@link Method}
 * (for parameter and return types), the call arguments in declared order, and
 * the {@code @ModularService} interface itself.
 *
 * <p>{@code serviceInterface} is carried separately from {@code method.getDeclaringClass()}
 * deliberately: if the interface being invoked extends another interface, a method inherited
 * from the parent reports the parent as its declaring class, not the full service interface a
 * transport was configured against -- that would silently disagree with the interface the
 * dispatching side resolves the same call through.
 */
public record ServiceInvocation(
        String serviceName,
        int serviceVersion,
        Class<?> serviceInterface,
        String methodName,
        Method method,
        Object[] args) {
}
