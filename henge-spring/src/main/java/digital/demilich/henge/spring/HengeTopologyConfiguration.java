package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Serves this process's topology ({@link HengeTopologyController}) when
 * {@code henge.topology.enabled=true}, and does nothing otherwise. {@link HengeConfiguration}
 * imports it; so does {@code henge-spring-boot-starter} in a servlet web application. Needs
 * {@link EnableHengeServices} on the application, like everything else here.
 */
@Configuration
@Conditional(HengeTopologyConfiguration.Enabled.class)
public class HengeTopologyConfiguration {

    static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return new HengeProperties(context.getEnvironment()).isTopologyEnabled();
        }
    }

    @Bean
    HengeTopologyController hengeTopologyController(ApplicationContext applicationContext, HengeTopologyCatalog catalog,
            HengeServiceRegistry registry, ObjectProvider<SystemEphemeralDatastore> datastore, Environment environment) {
        HengeProperties properties = new HengeProperties(environment);
        HengeTopologyReport report = new HengeTopologyReport(
                applicationContext, catalog, registry, datastore.getIfAvailable(), properties, environment);
        return new HengeTopologyController(report, HengeTransportSupport.objectMapper(), properties);
    }
}
