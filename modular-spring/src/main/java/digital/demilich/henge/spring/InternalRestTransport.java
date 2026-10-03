package digital.demilich.henge.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.ServiceTransport;
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

    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, ModularProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        if (url == null || url.isBlank()) {
            throw new RemoteServiceException("No url configured for modular service '" + invocation.serviceName()
                    + "' version '" + invocation.serviceVersion() + "' (set modular.services." + invocation.serviceName()
                    + ".url, modular.services." + invocation.serviceName() + ".versions." + invocation.serviceVersion()
                    + ".url for a per-version override, or modular.remote-url-template for a shared convention)");
        }

        String uri = url + properties.getServerPathPrefix() + "/" + invocation.serviceName() + "/"
                + invocation.serviceVersion() + "/" + invocation.methodName();

        ArrayNode body = objectMapper.createArrayNode();
        for (Object arg : invocation.args()) {
            body.add(objectMapper.valueToTree(arg));
        }

        String secret = properties.getTransportSecret();

        String responseBody;
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
                    .body(String.class);
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
        if (returnType == void.class || returnType == Void.class || responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(responseBody);
            return objectMapper.convertValue(tree, objectMapper.getTypeFactory().constructType(invocation.method().getGenericReturnType()));
        } catch (Exception e) {
            throw new RemoteServiceException("Failed to deserialize response from modular service '"
                    + invocation.serviceName() + "#" + invocation.methodName() + "'", e);
        }
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
