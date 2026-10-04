package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client-side {@link ServiceTransport}: dispatches a {@code @ModularService} call as
 * {@code POST {baseUrl}{pathPrefix}/{service}/{version}/{method}} with a JSON array of
 * arguments, matching {@link ModularDispatcherController} on the receiving end.
 */
class InternalRestTransport implements ServiceTransport, BeanClassLoaderAware {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ModularProperties properties;

    /**
     * Where remote exception types are looked up: the application's own classloader (what loaded
     * the beans that threw them), not the loader of whichever interface declares the called method
     * -- that one is {@code null} for an inherited JDK interface method and can be a parent loader
     * that can't see the application's exception classes (e.g. under Spring Boot DevTools).
     */
    private volatile ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

    private final AdvertisedEndpoints advertisedEndpoints;

    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, ModularProperties properties) {
        this(restClient, objectMapper, properties, null);
    }

    /** @param advertisedEndpoints where to look for a service with no configured url; null to not look */
    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, ModularProperties properties,
            AdvertisedEndpoints advertisedEndpoints) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.advertisedEndpoints = advertisedEndpoints;
        requireKnownPlaceholders(properties.getRemoteUrlTemplate());
        properties.getServerPathPrefix(); // validated at startup, not on the first call
    }

    /**
     * Checked at startup: an unknown placeholder (say {@code {namespace}}) would otherwise fail every
     * call to every service that falls back to the template, and only when the call is made.
     */
    private static void requireKnownPlaceholders(String template) {
        if (template == null) {
            return;
        }
        Matcher placeholder = PLACEHOLDER.matcher(template);
        while (placeholder.find()) {
            if (!placeholder.group().equals("{service}") && !placeholder.group().equals("{version}")) {
                throw new IllegalStateException("modular.remote-url-template '" + template + "' contains "
                        + placeholder.group() + "; only {service} and {version} are substituted");
            }
        }
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    @Override
    public Object invoke(ServiceInvocation invocation) {
        String url = properties.service(invocation.serviceName()).resolveUrl(invocation.serviceVersion());
        if (url == null || url.isBlank()) {
            url = resolveFromTemplate(invocation.serviceName(), invocation.serviceVersion());
        }
        if ((url == null || url.isBlank()) && advertisedEndpoints != null) {
            url = advertisedEndpoints.next(invocation.serviceName(), invocation.serviceVersion());
        }
        if (url == null || url.isBlank()) {
            throw new RemoteServiceException("No url configured for modular service '" + invocation.serviceName()
                    + "' version '" + invocation.serviceVersion() + "', and no process advertises it (set modular.services."
                    + invocation.serviceName() + ".url, modular.services." + invocation.serviceName() + ".versions."
                    + invocation.serviceVersion() + ".url for a per-version override, modular.remote-url-template for a "
                    + "shared convention, or run a process that hosts it with modular.advertise.url set)");
        }

        // "http://audit:8080/" is a natural way to write a base URL; without this it would produce
        // "//_modular/...", which the server doesn't route.
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String endpoint = url + properties.getServerPathPrefix() + "/" + invocation.serviceName() + "/"
                + invocation.serviceVersion() + "/" + invocation.methodName();
        // A URI, not a String: RestClient would treat a String as a URI template and try to expand
        // any '{...}' in it.
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new RemoteServiceException("Invalid url '" + endpoint + "' for modular service '" + invocation.serviceName()
                    + "' version '" + invocation.serviceVersion() + "' -- check modular.services." + invocation.serviceName()
                    + ".url (or its per-version override) and modular.remote-url-template", e);
        }

        byte[] body = writeArguments(invocation);

        String secret = properties.getTransportSecret();

        byte[] responseBody;
        try {
            responseBody = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (secret != null && !secret.isBlank()) {
                            headers.set(ModularDispatcherController.SECRET_HEADER, secret);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            RemoteServiceException fallback = new RemoteServiceException("Modular service call failed: " + invocation.serviceName() + "#"
                    + invocation.methodName() + " -> " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
            throw RemoteExceptionReconstructor.reconstruct(
                    e.getResponseBodyAsString(), beanClassLoader, objectMapper, fallback);
        } catch (RestClientException e) {
            // The immediate exception's own message is often a generic wrapper (e.g. "Error while
            // extracting response..."); the actually-useful detail -- "connect timed out",
            // "Read timed out" -- is on the root cause.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new RemoteServiceException("Modular service call failed: " + invocation.serviceName() + "#"
                    + invocation.methodName() + " (" + root.getMessage() + ")", e);
        }

        Class<?> returnType = invocation.method().getReturnType();
        if (returnType == void.class || returnType == Void.class || responseBody == null || responseBody.length == 0) {
            return null;
        }
        // Read straight into the declared return type, not via a JsonNode tree, which would hold a
        // JSON decimal as a double and lose a BigDecimal's precision and scale.
        try {
            return objectMapper.readValue(responseBody, objectMapper.getTypeFactory().constructType(invocation.method().getGenericReturnType()));
        } catch (Exception e) {
            throw new RemoteServiceException("Failed to deserialize response from modular service '"
                    + invocation.serviceName() + "#" + invocation.methodName() + "'", e);
        }
    }

    /**
     * The arguments as a JSON array, each written by the transport mapper as its declared parameter
     * type -- and sent as bytes, so RestClient's own message converters (and their ObjectMapper)
     * never touch them.
     */
    private byte[] writeArguments(ServiceInvocation invocation) {
        Type[] parameterTypes = invocation.method().getGenericParameterTypes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = objectMapper.createGenerator(out)) {
            generator.writeStartArray();
            for (int i = 0; i < parameterTypes.length; i++) {
                objectMapper.writerFor(objectMapper.getTypeFactory().constructType(parameterTypes[i]))
                        .writeValue(generator, invocation.args()[i]);
            }
            generator.writeEndArray();
        } catch (IOException e) {
            throw new RemoteServiceException("Failed to serialize arguments for modular service '"
                    + invocation.serviceName() + "#" + invocation.methodName() + "'", e);
        }
        return out.toByteArray();
    }

    /**
     * Fallback used when a service has no explicit {@code url} configured: substitutes
     * {@code {service}} and {@code {version}} in {@code modular.remote-url-template} with the
     * service's name and version, e.g. {@code http://{service}.default.svc.cluster.local:8080} ->
     * {@code http://audit-service.default.svc.cluster.local:8080}, or
     * {@code http://{service}-v{version}.default.svc.cluster.local:8080} for a per-version split.
     * {@code {service}}-only templates keep working unchanged, since {@code {version}} substitution
     * is a no-op when the placeholder isn't present. Package-private (rather than {@code private})
     * so it's directly, deterministically testable without needing a real HTTP call.
     */
    String resolveFromTemplate(String serviceName, int serviceVersion) {
        String template = properties.getRemoteUrlTemplate();
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("{service}", serviceName).replace("{version}", String.valueOf(serviceVersion));
    }
}
