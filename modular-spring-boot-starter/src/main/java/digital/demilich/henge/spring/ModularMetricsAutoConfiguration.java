package digital.demilich.henge.spring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The system's own meters (see {@link MicrometerSystemMetrics} and {@link ModularHostedGauges}) once the
 * application has a {@link MeterRegistry}, which Boot's Actuator provides. Without one, or without
 * Micrometer, nothing is reported and nothing is loaded.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class ModularMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MicrometerSystemMetrics modularSystemMetrics(MeterRegistry registry) {
        return new MicrometerSystemMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ModularHostedGauges modularHostedGauges(MeterRegistry registry, ApplicationContext applicationContext) {
        return new ModularHostedGauges(registry, applicationContext);
    }
}
