package com.demilich.horde.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serves this process's embedded modular services over {@code /_modular/**}. A plain-Spring
 * consumer that only wants to <em>call</em> other services, never host any, imports
 * {@link ModularTransportConfiguration} alone and skips this class — unlike
 * {@code modular-spring-boot-starter}, there's no {@code modular.server.enabled} property gate
 * here; "should this process serve requests" is a code-level choice (which configuration classes
 * you import), not a runtime conditional.
 */
@Configuration
public class ModularDispatcherConfiguration {

    @Bean
    public ModularDispatcherController modularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ObjectMapper objectMapper) {
        return new ModularDispatcherController(applicationContext, registry, objectMapper);
    }
}
