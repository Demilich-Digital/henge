package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the dispatcher needs to know about one (interface, version) pair that THIS process
 * hosts an embedded implementation of: its identity, the exact bean to invoke it on — looked up
 * by {@code beanName}, not by type, since multiple versions of the same interface may share this
 * process — and its RPC-method-name -> reflected {@link Method} table.
 */
public record ModularServiceDescriptor(
        String name, int version, Class<?> interfaceType, String beanName, Map<String, Method> methods, String contractFingerprint) {

    static ModularServiceDescriptor of(String name, int version, Class<?> interfaceType, String beanName) {
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
        return new ModularServiceDescriptor(name, version, interfaceType, beanName, Map.copyOf(methods), fingerprint(interfaceType));
    }

    private static String rpcName(Method method) {
        ServiceMethod override = method.getAnnotation(ServiceMethod.class);
        return (override != null && !override.name().isBlank()) ? override.name() : method.getName();
    }

    /**
     * A stable hash of every method on {@code interfaceType}'s full signature (RPC name, generic
     * parameter types, generic return type) — used to detect two different <em>builds</em> of the
     * "same" {@code @ModularService} interface disagreeing on shape (e.g. two reordered {@code
     * String} parameters), which positional JSON binding plus a matching version string alone
     * can't catch. Computed identically on both ends of the wire: the dispatcher from its
     * {@link ModularServiceDescriptor}, {@code InternalRestTransport} from the calling interface's
     * own {@code Class} — see {@code modular.transport.verify-contract} in {@link
     * ModularProperties}.
     *
     * <p>Generic types are used deliberately, not erased ones: the dispatcher itself binds
     * arguments against {@link Method#getGenericParameterTypes()} (see {@code
     * ModularDispatcherController.bindArguments}), so a {@code List<String>} that became a {@code
     * List<Integer>} between builds is exactly the kind of skew this needs to catch. Method
     * signatures are sorted lexicographically before hashing — {@link Class#getMethods()}'s
     * iteration order is unspecified by the JDK, so relying on it would make the fingerprint
     * unstable across JVM vendors/versions for the exact same interface.
     */
    static String fingerprint(Class<?> interfaceType) {
        List<String> signatures = new ArrayList<>();
        for (Method method : interfaceType.getMethods()) {
            StringBuilder signature = new StringBuilder(rpcName(method)).append('(');
            Type[] paramTypes = method.getGenericParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) {
                    signature.append(',');
                }
                signature.append(paramTypes[i].getTypeName());
            }
            signature.append(')').append(':').append(method.getGenericReturnType().getTypeName());
            signatures.add(signature.toString());
        }
        Collections.sort(signatures);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String signature : signatures) {
                digest.update(signature.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (see MessageDigest's Javadoc) -- unreachable.
            throw new AssertionError(e);
        }
    }
}
