package digital.demilich.henge.spring;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Convenience: imports {@link HengeTransportConfiguration}, {@link HengeTopologyConfiguration} and
 * {@link HengeDispatcherConfiguration} — the plain-Spring equivalent of what
 * {@code henge-spring-boot-starter}'s autoconfiguration wires up automatically. Pair with
 * {@link EnableHengeServices} on your own {@code @Configuration} class.
 */
@Configuration
@Import({HengeTransportConfiguration.class, HengeDispatcherConfiguration.class, HengeTopologyConfiguration.class})
public class HengeConfiguration {
}
