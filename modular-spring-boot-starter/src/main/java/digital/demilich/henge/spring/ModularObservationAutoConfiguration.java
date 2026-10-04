package digital.demilich.henge.spring;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Observes every service call and every dispatch (see {@link ObservationServiceCallInterceptor} and
 * {@link ObservationServiceDispatchObserver}) once the application has an {@link ObservationRegistry},
 * which Boot's Actuator provides and connects to a {@code MeterRegistry} and a tracer when they exist.
 * Without one, or without Micrometer, nothing is observed and nothing is loaded.
 */
@AutoConfiguration(afterName = "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration")
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
public class ModularObservationAutoConfiguration {

    /** Outermost, so a call's time includes any other interceptor's. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    ObservationServiceCallInterceptor modularCallObservation(ObservationRegistry registry) {
        return new ObservationServiceCallInterceptor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ObservationServiceDispatchObserver modularDispatchObservation(ObservationRegistry registry) {
        return new ObservationServiceDispatchObserver(registry);
    }
}
