package digital.demilich.henge.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Serves this process's embedded modular services over {@code /_modular/**}. A plain-Spring
 * consumer that only wants to <em>call</em> other services, never host any, imports
 * {@link ModularTransportConfiguration} alone and skips this class — unlike
 * {@code modular-spring-boot-starter}, there's no {@code modular.server.enabled} property gate
 * here; "should this process serve requests" is a code-level choice (which configuration classes
 * you import), not a runtime conditional.
 *
 * <p>Builds its own {@code ObjectMapper} via {@link ModularTransportSupport} rather than injecting
 * an unqualified bean — this class must work standalone (no {@link ModularTransportConfiguration}
 * required), and an unqualified {@code ObjectMapper} injection point risks silently binding to a
 * differently-configured mapper than the client side of the wire uses. Builds its own
 * {@link ModularProperties} from the injected {@link Environment} for the same standalone reason
 * (only {@code Environment} is guaranteed present in any Spring context; a {@code ModularProperties}
 * bean is only guaranteed to exist when {@link ModularTransportConfiguration} is also imported).
 */
@Configuration
public class ModularDispatcherConfiguration {

    @Bean
    public ModularDispatcherController modularDispatcherController(ModularServiceRegistry registry, Environment environment) {
        return new ModularDispatcherController(registry, ModularTransportSupport.objectMapper(), new ModularProperties(environment));
    }
}
