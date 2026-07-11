package io.modular.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single generic endpoint serving every {@code @ModularService} this process embeds:
 * {@code POST {pathPrefix}/{service}/{version}/{method}}. Routes are validated strictly
 * against {@link ModularServiceRegistry} — no reflection target outside that pre-built,
 * startup-time table is ever reachable.
 */
@RestController
@RequestMapping("${modular.server.path-prefix:/_modular}")
class ModularDispatcherController {

    private final ApplicationContext applicationContext;
    private final ModularServiceRegistry registry;
    private final ObjectMapper objectMapper;

    ModularDispatcherController(ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{service}/{version}/{method}")
    public ResponseEntity<?> dispatch(
            @PathVariable("service") String service,
            @PathVariable("version") String version,
            @PathVariable("method") String method,
            @RequestBody(required = false) JsonNode body) {

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
        Object[] args = bindArguments(targetMethod, body);

        Object result;
        try {
            result = targetMethod.invoke(bean, args);
        } catch (InvocationTargetException e) {
            throw new ModularDispatchException(HttpStatus.INTERNAL_SERVER_ERROR,
                    describeFailure(service, method, e.getCause()));
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
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }
}
