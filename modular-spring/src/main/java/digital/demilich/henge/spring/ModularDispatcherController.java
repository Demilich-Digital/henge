package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.ErrorStatus;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private static final Log log = LogFactory.getLog(ModularDispatcherController.class);

    private final ApplicationContext applicationContext;
    private final ModularServiceRegistry registry;
    private final ObjectMapper objectMapper;
    private final String configuredSecret;

    ModularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper, ModularProperties properties) {
        this.applicationContext = applicationContext;
        this.registry = registry;
        this.objectMapper = objectMapper;
        String secret = properties.getTransportSecret();
        this.configuredSecret = (secret == null || secret.isBlank()) ? null : secret;
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
            @PathVariable("version") int version,
            @PathVariable("method") String method,
            @RequestHeader(value = SECRET_HEADER, required = false) String providedSecret,
            InputStream requestBody) {

        // The body is taken as a raw stream, not @RequestBody JsonNode: Spring would parse a
        // @RequestBody before this method runs, letting an unauthenticated caller make the server
        // parse arbitrary JSON (and answer 400 instead of 403) before the secret is ever checked.
        requireValidSecret(providedSecret);

        ModularServiceDescriptor descriptor = registry.find(service, version)
                .orElseThrow(() -> new ModularDispatchException(HttpStatus.NOT_FOUND,
                        "This process does not host modular service '" + service + "' version '" + version + "'"));

        Method targetMethod = descriptor.methods().get(method);
        if (targetMethod == null) {
            throw new ModularDispatchException(HttpStatus.NOT_FOUND,
                    "Modular service '" + service + "' has no method '" + method + "'");
        }

        // Looked up by bean name, not just type: multiple versions of the same interface may be
        // embedded in this process simultaneously, which would make a type-only lookup ambiguous.
        Object bean = applicationContext.getBean(descriptor.beanName(), descriptor.interfaceType());
        Object[] args = bindArguments(targetMethod, readBody(requestBody));

        Object result;
        try {
            result = targetMethod.invoke(bean, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new ModularDispatchException(statusFor(cause),
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

    private JsonNode readBody(InputStream requestBody) {
        try {
            return objectMapper.readTree(requestBody);
        } catch (JsonProcessingException e) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
        } catch (IOException e) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Failed to read request body");
        }
    }

    private Object[] bindArguments(Method method, JsonNode body) {
        Class<?>[] paramTypes = method.getParameterTypes();
        boolean noBody = body == null || body.isNull() || body.isMissingNode();
        if (!noBody && !body.isArray()) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                    "Request body must be a JSON array of arguments for " + method.getName());
        }
        int provided = noBody ? 0 : body.size();
        if (provided != paramTypes.length) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                    "Expected " + paramTypes.length + " argument(s) for " + method.getName() + " but received " + provided);
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            try {
                args[i] = objectMapper.convertValue(body.get(i),
                        objectMapper.getTypeFactory().constructType(method.getGenericParameterTypes()[i]));
            } catch (RuntimeException e) {
                // Not just IllegalArgumentException (Jackson's wrapper for malformed JSON): a custom
                // deserializer can throw anything, e.g. ImmutableList rejecting a null element.
                throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                        "Failed to bind argument " + i + " of " + method.getName());
            }
        }
        return args;
    }

    /**
     * {@code 500} unless the exception's class (or a superclass) carries {@link ErrorStatus} with a
     * valid {@code 4xx}/{@code 5xx} code. {@code 500} is deliberately what this framework answers
     * for failures it can't classify, so an annotation is the only way to get anything else.
     */
    static HttpStatusCode statusFor(Throwable cause) {
        ErrorStatus annotation = cause.getClass().getAnnotation(ErrorStatus.class);
        if (annotation == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        int code = annotation.value();
        if (code < 400 || code > 599) {
            log.warn("Ignoring @ErrorStatus(" + code + ") on " + cause.getClass().getName()
                    + " -- must be a 4xx or 5xx status; answering 500 instead.");
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatusCode.valueOf(code);
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
