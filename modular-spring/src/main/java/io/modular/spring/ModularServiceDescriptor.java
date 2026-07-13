package io.modular.spring;

import io.modular.core.ServiceMethod;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the dispatcher needs to know about one (interface, version) pair that THIS process
 * hosts an embedded implementation of: its identity, the exact bean to invoke it on — looked up
 * by {@code beanName}, not by type, since multiple versions of the same interface may share this
 * process — and its RPC-method-name -> reflected {@link Method} table.
 */
public record ModularServiceDescriptor(
        String name, String version, Class<?> interfaceType, String beanName, Map<String, Method> methods) {

    static ModularServiceDescriptor of(String name, String version, Class<?> interfaceType, String beanName) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : interfaceType.getMethods()) {
            String rpcName = rpcName(method);
            Method existing = methods.putIfAbsent(rpcName, method);
            if (existing != null) {
                throw new IllegalStateException("Modular service '" + name + "' has two methods that resolve to the same "
                        + "RPC name '" + rpcName + "' (" + existing + " and " + method + "); overloaded methods are not "
                        + "supported, use @ServiceMethod(name = ...) to disambiguate");
            }
        }
        return new ModularServiceDescriptor(name, version, interfaceType, beanName, Map.copyOf(methods));
    }

    private static String rpcName(Method method) {
        ServiceMethod override = method.getAnnotation(ServiceMethod.class);
        return (override != null && !override.name().isBlank()) ? override.name() : method.getName();
    }
}
