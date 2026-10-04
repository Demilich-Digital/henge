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
 * Serves this process's topology ({@link ModularTopologyController}) when
 * {@code modular.topology.enabled=true}, and does nothing otherwise. {@link ModularConfiguration}
 * imports it; so does {@code modular-spring-boot-starter} in a servlet web application. Needs
 * {@link EnableModularServices} on the application, like everything else here.
 */
@Configuration
@Conditional(ModularTopologyConfiguration.Enabled.class)
public class ModularTopologyConfiguration {

    static class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return new ModularProperties(context.getEnvironment()).isTopologyEnabled();
        }
    }

    @Bean
    ModularTopologyController modularTopologyController(ApplicationContext applicationContext, ModularTopologyCatalog catalog,
            ModularServiceRegistry registry, ObjectProvider<SystemEphemeralDatastore> datastore, Environment environment) {
        ModularProperties properties = new ModularProperties(environment);
        ModularTopologyReport report = new ModularTopologyReport(
                applicationContext, catalog, registry, datastore.getIfAvailable(), properties, environment);
        return new ModularTopologyController(report, ModularTransportSupport.objectMapper(), properties);
    }
}
