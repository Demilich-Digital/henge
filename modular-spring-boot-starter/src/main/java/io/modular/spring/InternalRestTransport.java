package io.modular.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.modular.core.RemoteServiceException;
import io.modular.core.ServiceInvocation;
import io.modular.core.ServiceTransport;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client-side {@link ServiceTransport}: dispatches a {@code @ModularService} call as
 * {@code POST {baseUrl}{pathPrefix}/{service}/{version}/{method}} with a JSON array of
 * arguments, matching {@link ModularDispatcherController} on the receiving end.
 */
class InternalRestTransport implements ServiceTransport {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ModularProperties properties;

    InternalRestTransport(RestClient restClient, ObjectMapper objectMapper, ModularProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Object invoke(ServiceInvocation invocation) {
        ModularProperties.ServiceConfig config = properties.getServices().get(invocation.serviceName());
        String url = (config != null) ? config.resolveUrl(invocation.serviceVersion()) : null;
        if (url == null || url.isBlank()) {
            throw new RemoteServiceException("No url configured for modular service '" + invocation.serviceName()
                    + "' version '" + invocation.serviceVersion() + "' (set modular.services." + invocation.serviceName()
                    + ".url, or modular.services." + invocation.serviceName() + ".versions." + invocation.serviceVersion()
                    + ".url for a per-version override)");
        }

        String uri = url + properties.getServer().getPathPrefix() + "/" + invocation.serviceName() + "/"
                + invocation.serviceVersion() + "/" + invocation.methodName();

        ArrayNode body = objectMapper.createArrayNode();
        for (Object arg : invocation.args()) {
            body.add(objectMapper.valueToTree(arg));
        }

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RemoteServiceException("Modular service call failed: " + invocation.serviceName() + "#"
                    + invocation.methodName() + " -> " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new RemoteServiceException(
                    "Modular service call failed: " + invocation.serviceName() + "#" + invocation.methodName(), e);
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
}
