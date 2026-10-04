package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceInvocation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Observes every call a caller makes to a {@code @ModularService}, embedded or remote alike, as
 * {@value #NAME}: a timer (and a trace span) wherever the application's {@link ObservationRegistry}
 * has handlers for them, which Spring Boot's does once a {@code MeterRegistry} or a tracer is present.
 * The call is open as the current observation while it runs, so whatever it calls in turn (the
 * transport's HTTP request) is a child of it.
 *
 * <p>All of its key values are low cardinality, and bounded by the code rather than by the traffic:
 * <ul>
 *   <li>{@code henge.service}, {@code henge.version} and {@code henge.method}: from the interface;
 *   <li>{@code henge.mode}: {@code embedded} or {@code internal-rest}, how this call was fulfilled.
 * </ul>
 * A call that throws is an observation with that error, so the failure rate and its exception type come
 * with the timer.
 *
 * <p>Boot registers it when an {@link ObservationRegistry} bean exists. Without Boot, declare it as a bean.
 */
public class ObservationServiceCallInterceptor implements ServiceCallInterceptor {

    /** The observation's name, and so the timer's. */
    public static final String NAME = "henge.call";

    private final ObservationRegistry registry;

    public ObservationServiceCallInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object intercept(ServiceInvocation invocation, Chain chain) throws Throwable {
        Observation observation = Observation.createNotStarted(NAME, registry)
                .lowCardinalityKeyValue("henge.service", invocation.serviceName())
                .lowCardinalityKeyValue("henge.version", String.valueOf(invocation.serviceVersion()))
                .lowCardinalityKeyValue("henge.method", invocation.methodName())
                .lowCardinalityKeyValue("henge.mode", chain.mode().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            return chain.proceed();
        } catch (Throwable failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
