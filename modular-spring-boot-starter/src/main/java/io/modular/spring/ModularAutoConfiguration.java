package io.modular.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modular.core.ServiceTransport;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Wires the modular services runtime. Interface discovery itself only happens when the
 * application also carries {@link EnableModularServices} — without it, this still configures
 * the transport/dispatcher machinery, but {@link ModularServiceRegistry} stays empty.
 */
@AutoConfiguration
@EnableConfigurationProperties(ModularProperties.class)
public class ModularAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModularServiceRegistry modularServiceRegistry() {
        return new ModularServiceRegistry(List.of());
    }

    @Bean
    @ConditionalOnMissingBean
    RestClient modularRestClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    ServiceTransport internalRestTransport(RestClient modularRestClient, ObjectMapper objectMapper, ModularProperties properties) {
        return new InternalRestTransport(modularRestClient, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "modular.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModularDispatcherController modularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper) {
        return new ModularDispatcherController(applicationContext, registry, objectMapper);
    }
}
