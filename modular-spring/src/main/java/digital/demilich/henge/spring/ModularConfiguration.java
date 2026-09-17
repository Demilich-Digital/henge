package digital.demilich.henge.spring;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Convenience: imports both {@link ModularTransportConfiguration} and
 * {@link ModularDispatcherConfiguration} — the plain-Spring equivalent of what
 * {@code modular-spring-boot-starter}'s autoconfiguration wires up automatically. Pair with
 * {@link EnableModularServices} on your own {@code @Configuration} class.
 */
@Configuration
@Import({ModularTransportConfiguration.class, ModularDispatcherConfiguration.class})
public class ModularConfiguration {
}
