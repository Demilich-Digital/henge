package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the dispatcher needs to know about one (interface, version) pair that THIS process
 * hosts an embedded implementation of: its identity, the exact bean to invoke it on — looked up
 * by {@code beanName}, not by type, since multiple versions of the same interface may share this
 * process — and its RPC-method-name -> reflected {@link Method} table.
 */
public record HengeServiceDescriptor(
        String name, int version, Class<?> interfaceType, String beanName, Map<String, Method> methods) {

    static HengeServiceDescriptor of(String name, int version, Class<?> interfaceType, String beanName) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : interfaceType.getMethods()) {
            // getMethods() includes the interface's own static methods; they're helpers, not
            // operations, and must not be reachable over /_henge (the processor rejects them, but
            // an interface may be compiled without it).
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            // Reflective calls from the dispatcher into a non-public interface (a package-private
            // one is legal, and works embedded) otherwise fail with IllegalAccessException.
            method.trySetAccessible();
            String rpcName = rpcName(method);
            Method existing = methods.putIfAbsent(rpcName, method);
            // getMethods() lists the same method inherited from two unrelated superinterfaces
            // (Svc extends A, B, both declaring String a()) once per superinterface; that's one method.
            if (existing != null && !sameSignature(existing, method)) {
                throw new IllegalStateException("Henge service '" + name + "' has two methods that resolve to the same "
                        + "RPC name '" + rpcName + "' (" + existing + " and " + method + "); overloaded methods are not "
                        + "supported, use @ServiceMethod(name = ...) to disambiguate");
            }
        }
        return new HengeServiceDescriptor(name, version, interfaceType, beanName, Map.copyOf(methods));
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName()) && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    /** The dispatch-path name of {@code method}: {@code @ServiceMethod(name)} if set, else the Java name. */
    static String rpcName(Method method) {
        ServiceMethod override = method.getAnnotation(ServiceMethod.class);
        return (override != null && !override.name().isBlank()) ? override.name() : method.getName();
    }
}
