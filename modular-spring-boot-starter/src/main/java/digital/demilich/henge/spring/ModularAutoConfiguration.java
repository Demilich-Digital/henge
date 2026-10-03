package digital.demilich.henge.spring;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
 *
 * <p>The dispatcher's {@code ObjectMapper} is built via {@link ModularTransportSupport} rather than
 * injected as an unqualified bean, for the same reason as {@code modular-spring}'s
 * {@link ModularDispatcherConfiguration} — see its Javadoc.
 */
@AutoConfiguration
@Import(ModularTransportConfiguration.class)
public class ModularAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModularServiceRegistry modularServiceRegistry() {
        return new ModularServiceRegistry(List.of());
    }

    /**
     * Only in a servlet web application -- a process that serves no HTTP has nothing to dispatch to it.
     * Backs off when the application already imported {@link ModularDispatcherConfiguration} itself.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "modular.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModularDispatcherController modularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ModularProperties modularProperties) {
        return new ModularDispatcherController(applicationContext, registry, ModularTransportSupport.objectMapper(), modularProperties);
    }
}
