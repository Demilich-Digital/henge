package com.demilich.horde.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Boot-specific classpath auto-detection layer on top of the plain-Spring
 * {@link ModularTransportConfiguration}: unconditionally imports the transport wiring (RestClient,
 * {@code ServiceTransport}, {@link ModularProperties}), then adds Boot-only conveniences that
 * don't have a plain-Spring equivalent — an empty {@link ModularServiceRegistry} fallback when
 * {@link EnableModularServices} wasn't used, and gating the dispatcher controller behind
 * {@code modular.server.enabled}. See {@code modular-spring}'s {@link ModularConfiguration} for
 * the plain-Spring path, where "should this process serve requests" is a code-level `@Import`
 * choice instead of a property.
 */
@AutoConfiguration
@Import(ModularTransportConfiguration.class)
public class ModularAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModularServiceRegistry modularServiceRegistry() {
        return new ModularServiceRegistry(List.of());
    }

    @Bean
    @ConditionalOnProperty(prefix = "modular.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModularDispatcherController modularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper) {
        return new ModularDispatcherController(applicationContext, registry, objectMapper);
    }
}
