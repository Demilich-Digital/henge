package digital.demilich.henge.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single generic endpoint serving every {@code @ModularService} this process embeds:
 * {@code POST {pathPrefix}/{service}/{version}/{method}}. Routes are validated strictly
 * against {@link ModularServiceRegistry} — no reflection target outside that pre-built,
 * startup-time table is ever reachable.
 *
 * <p>Unauthenticated and on by default otherwise — when {@code modular.transport.secret} is
 * unset, every embedded {@code @ModularService} method is reachable by anyone who can reach this
 * process's HTTP port, which logs a startup warning. See the README's security section; a
 * separate listen port for {@code /_modular} (analogous to {@code management.server.port}) is
 * tracked as a follow-on, not yet built.
 */
@RestController
@RequestMapping("${modular.server.path-prefix:/_modular}")
class ModularDispatcherController {

    static final String SECRET_HEADER = "Modular-Internal-Secret";
    static final String FINGERPRINT_HEADER = "Modular-Contract-Fingerprint";

    private static final Log log = LogFactory.getLog(ModularDispatcherController.class);

    private final ApplicationContext applicationContext;
    private final ModularServiceRegistry registry;
    private final ObjectMapper objectMapper;
    private final String configuredSecret;
    private final boolean verifyContract;

    ModularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper, ModularProperties properties) {
        this.applicationContext = applicationContext;
        this.registry = registry;
        this.objectMapper = objectMapper;
        String secret = properties.getTransportSecret();
        this.configuredSecret = (secret == null || secret.isBlank()) ? null : secret;
        this.verifyContract = properties.isVerifyContractEnabled();
        if (this.configuredSecret == null) {
            log.warn("The modular dispatcher is enabled with no modular.transport.secret configured -- "
                    + "every embedded @ModularService method on this process is reachable by anyone who can "
                    + "reach its HTTP port. Set modular.transport.secret to require a shared secret on every "
                    + "internal-rest call.");
        }
    }

    @PostMapping("/{service}/{version}/{method}")
    public ResponseEntity<?> dispatch(
            @PathVariable("service") String service,
            @PathVariable("version") String version,
            @PathVariable("method") String method,
            @RequestHeader(value = SECRET_HEADER, required = false) String providedSecret,
            @RequestHeader(value = FINGERPRINT_HEADER, required = false) String clientFingerprint,
            @RequestBody(required = false) JsonNode body) {

        requireValidSecret(providedSecret);

        ModularServiceDescriptor descriptor = registry.find(service, version)
                .orElseThrow(() -> new ModularDispatchException(HttpStatus.NOT_FOUND,
                        "This process does not host modular service '" + service + "' version '" + version + "'"));

        requireMatchingContract(descriptor, clientFingerprint);

        Method targetMethod = descriptor.methods().get(method);
        if (targetMethod == null) {
            throw new ModularDispatchException(HttpStatus.NOT_FOUND,
                    "Modular service '" + service + "' has no method '" + method + "'");
        }

        // Looked up by bean name, not just type: multiple versions of the same interface may be
        // embedded in this process simultaneously, which would make a type-only lookup ambiguous.
        Object bean = applicationContext.getBean(descriptor.beanName(), descriptor.interfaceType());
        Object[] args = bindArguments(targetMethod, body);

        Object result;
        try {
            result = targetMethod.invoke(bean, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new ModularDispatchException(HttpStatus.INTERNAL_SERVER_ERROR,
                    describeFailure(service, method, cause), cause.getClass().getName(), cause.getMessage());
        } catch (IllegalAccessException e) {
            throw new ModularDispatchException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to invoke " + service + "#" + method);
        }

        if (targetMethod.getReturnType() == void.class) {
            return ResponseEntity.noContent().build();
        }
        // Returned as a JsonNode (rather than the raw object) so Jackson's message converter is
        // always the one that handles writing it — a raw String return value would otherwise be
        // routed through StringHttpMessageConverter as unquoted plain text instead of JSON.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(objectMapper.valueToTree(result));
    }

    /**
     * No-op when no secret is configured (the pre-secret, unauthenticated default). Uses
     * {@link MessageDigest#isEqual(byte[], byte[])} for the comparison -- guaranteed by its
     * Javadoc to take time independent of where the arrays first differ, unlike {@link
     * String#equals}, which would let a timing attack narrow down the secret one byte at a time.
     */
    private void requireValidSecret(String providedSecret) {
        if (configuredSecret == null) {
            return;
        }
        boolean valid = providedSecret != null
                && MessageDigest.isEqual(
                        providedSecret.getBytes(StandardCharsets.UTF_8), configuredSecret.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new ModularDispatchException(HttpStatus.FORBIDDEN, "Missing or invalid " + SECRET_HEADER + " header");
        }
    }

    /**
     * A missing header (an older client, or one with {@code modular.transport.verify-contract=false})
     * is treated as "unknown, don't block" rather than rejected -- this check exists to catch two
     * different <em>builds</em> of the same interface disagreeing, not to require every caller to
     * participate. Symmetric with the client: either side setting {@code verify-contract=false}
     * disables its own half of the check, so a deliberate mixed-build window doesn't require
     * coordinating the flag everywhere at once.
     */
    private void requireMatchingContract(ModularServiceDescriptor descriptor, String clientFingerprint) {
        if (!verifyContract || clientFingerprint == null || clientFingerprint.isBlank()) {
            return;
        }
        if (!clientFingerprint.equals(descriptor.contractFingerprint())) {
            throw new ModularDispatchException(HttpStatus.CONFLICT,
                    "Contract fingerprint mismatch for modular service '" + descriptor.name() + "' version '" + descriptor.version()
                            + "': client=" + clientFingerprint + " server=" + descriptor.contractFingerprint() + " -- the two "
                            + "processes were built from different versions of this interface. Set "
                            + "modular.transport.verify-contract=false on either side for a deliberate mixed-build window.");
        }
    }

    private Object[] bindArguments(Method method, JsonNode body) {
        Class<?>[] paramTypes = method.getParameterTypes();
        int provided = (body == null || body.isNull()) ? 0 : body.size();
        if (provided != paramTypes.length) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                    "Expected " + paramTypes.length + " argument(s) for " + method.getName() + " but received " + provided);
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            try {
                args[i] = objectMapper.convertValue(body.get(i),
                        objectMapper.getTypeFactory().constructType(method.getGenericParameterTypes()[i]));
            } catch (IllegalArgumentException e) {
                throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                        "Failed to bind argument " + i + " of " + method.getName());
            }
        }
        return args;
    }

    private static String describeFailure(String service, String method, Throwable cause) {
        String message = cause.getMessage();
        return "Modular service '" + service + "#" + method + "' threw " + cause.getClass().getName()
                + (message != null ? ": " + message : "");
    }

    @ExceptionHandler(ModularDispatchException.class)
    public ResponseEntity<Map<String, String>> handleDispatchException(ModularDispatchException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        // Only present when the failure was the target method's own business exception (see
        // RemoteExceptionReconstructor) -- absent for dispatch-level failures like 404/400.
        if (e.getRemoteExceptionType() != null) {
            body.put("exceptionType", e.getRemoteExceptionType());
            body.put("exceptionMessage", e.getRemoteExceptionMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
