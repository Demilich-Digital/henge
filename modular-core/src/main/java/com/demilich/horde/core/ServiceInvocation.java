package com.demilich.horde.core;

import java.lang.reflect.Method;

/**
 * Everything a {@link ServiceTransport} needs to carry out one remote call:
 * which service/version/method is being invoked, the reflected {@link Method}
 * (for parameter and return types), and the call arguments in declared order.
 */
public record ServiceInvocation(
        String serviceName,
        String serviceVersion,
        String methodName,
        Method method,
        Object[] args) {
}
