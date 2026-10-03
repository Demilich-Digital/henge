package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.ErrorStatus;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
 * <p>Authentication is optional: the security model is network isolation (see the README), so with
 * {@code modular.transport.secret} unset every embedded {@code @ModularService} method is open to
 * whoever can reach this process's HTTP port, which is the supported, expected configuration.
 * When the secret is set, it is required on every call.
 */
@RestController
@RequestMapping("${modular.server.path-prefix:/_modular}")
class ModularDispatcherController {

    static final String SECRET_HEADER = "Modular-Internal-Secret";

    private static final Log log = LogFactory.getLog(ModularDispatcherController.class);

    private final ApplicationContext applicationContext;
    private final ModularServiceRegistry registry;
    private final ObjectMapper objectMapper;
    private final SharedSecret secret;

    ModularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper, ModularProperties properties) {
        this.applicationContext = applicationContext;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.secret = SharedSecret.from(properties);
        if (!secret.isRequired()) {
            log.info("modular.transport.secret is not set: /_modular accepts calls without authentication and "
                    + "relies on network-level isolation.");
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
        Object[] args = readArguments(targetMethod, requestBody);

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
        // Written here by the transport mapper, typed by the declared return type, and sent as
        // bytes: no message converter (or the application's ObjectMapper) gets a say, and there's no
        // JsonNode in between -- see readArguments.
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(writeResult(targetMethod, result));
    }

    /** No-op when no secret is configured (the supported, network-isolated default). */
    private void requireValidSecret(String providedSecret) {
        if (!secret.accepts(providedSecret)) {
            throw new ModularDispatchException(HttpStatus.FORBIDDEN, "Missing or invalid " + SECRET_HEADER + " header");
        }
    }

    /**
     * Binds the body -- a JSON array, one element per parameter -- straight from the parser to each
     * parameter's declared type. Deliberately not through a {@code JsonNode} tree: a tree holds JSON
     * decimals as doubles, which silently loses a {@code BigDecimal}'s precision and scale. An empty
     * body or a JSON {@code null} means no arguments.
     */
    private Object[] readArguments(Method method, InputStream requestBody) {
        Object[] args = new Object[method.getParameterCount()];
        try (JsonParser parser = objectMapper.createParser(requestBody)) {
            JsonToken first = parser.nextToken();
            int provided = 0;
            if (first == JsonToken.START_ARRAY) {
                for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                    if (provided < args.length) {
                        args[provided] = readArgument(parser, method, provided);
                    } else {
                        parser.skipChildren();
                    }
                    provided++;
                }
            } else if (first != null && first != JsonToken.VALUE_NULL) {
                parser.skipChildren(); // reads through it, so a malformed body is still reported as invalid JSON
                requireEndOfInput(parser);
                throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                        "Request body must be a JSON array of arguments for " + method.getName());
            }
            requireEndOfInput(parser);
            if (provided != args.length) {
                throw new ModularDispatchException(HttpStatus.BAD_REQUEST,
                        "Expected " + args.length + " argument(s) for " + method.getName() + " but received " + provided);
            }
            return args;
        } catch (StreamReadException e) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
        } catch (IOException e) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Failed to read request body");
        }
    }

    private Object readArgument(JsonParser parser, Method method, int index) throws StreamReadException {
        try {
            return objectMapper.readValue(parser, objectMapper.getTypeFactory().constructType(method.getGenericParameterTypes()[index]));
        } catch (StreamReadException e) {
            throw e; // malformed JSON, not a binding problem
        } catch (IOException | RuntimeException e) {
            // Not just Jackson's own binding errors: a custom deserializer can throw anything, e.g.
            // ImmutableList rejecting a null element.
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Failed to bind argument " + index + " of " + method.getName());
        }
    }

    private static void requireEndOfInput(JsonParser parser) throws IOException {
        if (parser.nextToken() != null) {
            throw new ModularDispatchException(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
        }
    }

    private byte[] writeResult(Method method, Object result) {
        try {
            return objectMapper.writerFor(objectMapper.getTypeFactory().constructType(method.getGenericReturnType()))
                    .writeValueAsBytes(result);
        } catch (IOException e) {
            throw new ModularDispatchException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize the result of " + method.getName());
        }
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

    /**
     * Written by the transport mapper and sent as bytes, like a successful result: left to the
     * application's message converters, the body could come out as something the client can't parse
     * (plain Spring tries an XML converter before JSON whenever jackson-dataformat-xml is present),
     * and the original exception type would silently not be reconstructed.
     */
    @ExceptionHandler(ModularDispatchException.class)
    public ResponseEntity<byte[]> handleDispatchException(ModularDispatchException e) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        // Only present when the failure was the target method's own business exception (see
        // RemoteExceptionReconstructor) -- absent for dispatch-level failures like 404/400.
        if (e.getRemoteExceptionType() != null) {
            body.put("exceptionType", e.getRemoteExceptionType());
            body.put("exceptionMessage", e.getRemoteExceptionMessage());
        }
        return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsBytes(body));
    }
}
