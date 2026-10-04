package digital.demilich.henge.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Serves this process's embedded Henge services over {@code /_henge/**}. A plain-Spring
 * consumer that only wants to <em>call</em> other services, never host any, imports
 * {@link HengeTransportConfiguration} alone and skips this class — unlike
 * {@code henge-spring-boot-starter}, there's no {@code henge.server.enabled} property gate
 * here; "should this process serve requests" is a code-level choice (which configuration classes
 * you import), not a runtime conditional.
 *
 * <p>Builds its own {@code ObjectMapper} via {@link HengeTransportSupport} rather than injecting
 * an unqualified bean — this class must work standalone (no {@link HengeTransportConfiguration}
 * required), and an unqualified {@code ObjectMapper} injection point risks silently binding to a
 * differently-configured mapper than the client side of the wire uses. Builds its own
 * {@link HengeProperties} from the injected {@link Environment} for the same standalone reason
 * (only {@code Environment} is guaranteed present in any Spring context; a {@code HengeProperties}
 * bean is only guaranteed to exist when {@link HengeTransportConfiguration} is also imported).
 */
@Configuration
public class HengeDispatcherConfiguration {

    @Bean
    public HengeDispatcherController hengeDispatcherController(
            HengeServiceRegistry registry, Environment environment, ObjectProvider<ServiceDispatchObserver> observer) {
        return new HengeDispatcherController(registry, HengeTransportSupport.objectMapper(), new HengeProperties(environment),
                observer.getIfAvailable(() -> ServiceDispatchObserver.NONE));
    }
}
