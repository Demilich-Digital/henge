package io.modular.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modular.core.ServiceTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Plain-Spring core wiring: everything needed to <em>call</em> modular services (embedded or
 * remote) from this process, independent of whether it also <em>serves</em> any of them — see
 * {@link ModularDispatcherConfiguration} for that, or {@link ModularConfiguration} to import both
 * at once. Combined with {@link EnableModularServices} on your own {@code @Configuration} class,
 * this is the plain-Spring equivalent of {@code modular-spring-boot-starter}'s autoconfiguration.
 *
 * <p>Unlike the Boot starter, this deliberately does <em>not</em> provide a fallback empty
 * {@link ModularServiceRegistry} bean — always pair this with {@link EnableModularServices} on
 * your own {@code @Configuration} class, which registers the real one. Omitting it fails clearly
 * at startup ("no bean of type ModularServiceRegistry") rather than silently doing nothing.
 */
@Configuration
public class ModularTransportConfiguration {

    @Bean
    public static PropertySourcesPlaceholderConfigurer modularPropertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public ModularProperties modularProperties(Environment environment) {
        return new ModularProperties(environment);
    }

    @Bean
    public RestClient modularRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    public ObjectMapper modularObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ServiceTransport internalRestTransport(RestClient modularRestClient, ObjectMapper modularObjectMapper, ModularProperties modularProperties) {
        return new InternalRestTransport(modularRestClient, modularObjectMapper, modularProperties);
    }
}
