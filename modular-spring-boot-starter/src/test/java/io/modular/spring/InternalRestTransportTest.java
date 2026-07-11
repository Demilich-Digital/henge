package io.modular.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Focused, deterministic test for {@code modular.remote-url-template}'s {@code {service}}
 * substitution — exercised directly rather than over real HTTP (see
 * {@link ModularServiceRemoteDispatchIntegrationTest} for the end-to-end round trip) so it
 * doesn't depend on network/DNS timing.
 */
class InternalRestTransportTest {

    @Test
    void substitutesServiceNameIntoRemoteUrlTemplate() {
        ModularProperties properties = new ModularProperties();
        properties.setRemoteUrlTemplate("http://{service}.default.svc.cluster.local:8080");
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), new ObjectMapper(), properties);

        assertThat(transport.resolveFromTemplate("audit-service"))
                .isEqualTo("http://audit-service.default.svc.cluster.local:8080");
    }

    @Test
    void returnsNullWhenNoTemplateConfigured() {
        InternalRestTransport transport =
                new InternalRestTransport(RestClient.builder().build(), new ObjectMapper(), new ModularProperties());

        assertThat(transport.resolveFromTemplate("audit-service")).isNull();
    }
}
