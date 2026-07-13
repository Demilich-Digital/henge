package com.demilich.horde.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared construction for the modular transport's {@link ObjectMapper} and {@link RestClient}.
 * Deliberately not exposed as Spring beans (see {@link ModularTransportConfiguration} /
 * {@link ModularDispatcherConfiguration}) so they never collide with, or cause Boot's
 * autoconfiguration to back off from, an application's own Jackson/RestClient configuration.
 * Both the client side ({@link InternalRestTransport}) and the server side
 * ({@link ModularDispatcherController}) build their mapper through this one factory method so the
 * two ends of the wire provably agree on JSON handling.
 */
final class ModularTransportSupport {

    private ModularTransportSupport() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new HordeCollectionsModule());
    }

    /**
     * A plain-Spring (no Boot dependency) {@link RestClient} with finite connect/read timeouts —
     * {@code RestClient.builder().build()}'s defaults are infinite, which lets one hung remote
     * service pin a caller thread forever. Timeouts come from {@code modular.transport.connect-timeout}
     * / {@code modular.transport.read-timeout} ({@link ModularProperties}, milliseconds).
     */
    static RestClient restClient(ModularProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
