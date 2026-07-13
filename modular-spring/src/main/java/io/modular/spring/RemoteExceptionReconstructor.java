package io.modular.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modular.core.RemoteServiceException;
import java.lang.reflect.Constructor;

/**
 * Best-effort reconstruction of the exact exception a remote {@code @ModularService}
 * implementation threw, from the {@code exceptionType}/{@code exceptionMessage} fields
 * {@link ModularDispatcherController} includes in its error body -- so a caller of an
 * {@code internal-rest} service sees the same exception type an {@code embedded} caller would,
 * instead of always getting a generic {@link RemoteServiceException}.
 *
 * <p>Deliberately scoped to {@link RuntimeException} subtypes with a {@code (String)}
 * constructor: Java's dynamic proxies ({@link ModularServiceProxyFactoryBean}) can only let a
 * checked exception through to the caller if it's declared on the interface method's {@code
 * throws} clause, and there's no reliable way to check that here -- reconstructing one would
 * either fail unpredictably or surface as an opaque {@link
 * java.lang.reflect.UndeclaredThrowableException} anyway. Unchecked exceptions have no such
 * restriction, so those are the only case worth attempting; anything else falls back to the
 * caller-supplied {@code fallback} unchanged.
 */
final class RemoteExceptionReconstructor {

    private RemoteExceptionReconstructor() {
    }

    /**
     * @param responseBody the raw HTTP error response body (expected, but not required, to be the
     *     JSON shape {@link ModularDispatcherController#handleDispatchException} produces)
     * @param classLoader classloader to resolve {@code exceptionType} against -- the calling
     *     service interface's own, so lookup happens in the same classloading context the JDK
     *     proxy for it was created in
     * @param fallback returned unchanged whenever reconstruction isn't possible for any reason
     */
    static RuntimeException reconstruct(String responseBody, ClassLoader classLoader, ObjectMapper objectMapper, RemoteServiceException fallback) {
        String exceptionType;
        String exceptionMessage;
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode typeNode = node.get("exceptionType");
            if (typeNode == null || typeNode.isNull()) {
                return fallback;
            }
            exceptionType = typeNode.asText();
            JsonNode messageNode = node.get("exceptionMessage");
            exceptionMessage = (messageNode != null && !messageNode.isNull()) ? messageNode.asText() : null;
        } catch (Exception e) {
            return fallback;
        }

        // Reflectively loading and instantiating a class named by the remote process is
        // inherently unpredictable (missing class, unexpected static-initializer failure, an
        // exotic constructor with side effects, ...) -- any failure here must degrade to the
        // fallback, never propagate or crash the caller. Broader than this codebase's usual
        // precise ReflectiveOperationException catches, deliberately: the input crossed a
        // process (and trust) boundary, even though that boundary is already documented as
        // network-perimeter-only, same as the rest of internal-rest.
        try {
            Class<?> type = Class.forName(exceptionType, false, classLoader);
            if (!RuntimeException.class.isAssignableFrom(type)) {
                return fallback;
            }
            Constructor<?> constructor = type.getConstructor(String.class);
            RuntimeException instance = (RuntimeException) constructor.newInstance(exceptionMessage);
            try {
                instance.initCause(fallback);
            } catch (RuntimeException alreadyInitialized) {
                // Fine -- the reconstructed instance is still usable without the extra chaining.
            }
            return instance;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
