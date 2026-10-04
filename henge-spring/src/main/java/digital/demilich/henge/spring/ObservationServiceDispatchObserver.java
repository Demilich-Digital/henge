package digital.demilich.henge.spring;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Observes every request to {@code /_henge} as {@value #NAME}, the serving side of
 * {@link ObservationServiceCallInterceptor}'s {@code henge.call}: how long this process took to answer,
 * and with what.
 *
 * <p>Low cardinality key values:
 * <ul>
 *   <li>{@code henge.status}: the HTTP status answered, so a {@code 404} (not served here), a {@code 4xx}
 *       the service chose with {@code @ErrorStatus} and a {@code 500} are told apart;
 *   <li>{@code henge.exception}: the class of the business exception the method threw, else {@code none};
 *   <li>{@code henge.service}, {@code henge.version} and {@code henge.method}: only for a request that
 *       names something this process serves, else {@code none}. A request for anything else is the
 *       caller's own text, which would make the number of meters unbounded.
 * </ul>
 *
 * <p>Boot registers it when an {@link ObservationRegistry} bean exists. Without Boot, declare it as a bean.
 */
public class ObservationServiceDispatchObserver implements ServiceDispatchObserver {

    /** The observation's name, and so the timer's. */
    public static final String NAME = "henge.dispatch";

    private static final String NONE = "none";

    private final ObservationRegistry registry;

    public ObservationServiceDispatchObserver(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Scope start() {
        // Every key is present from the start, so meters of one name always have the same keys; the
        // ones a request fills in later replace these.
        Observation observation = Observation.createNotStarted(NAME, registry)
                .lowCardinalityKeyValue("henge.service", NONE)
                .lowCardinalityKeyValue("henge.version", NONE)
                .lowCardinalityKeyValue("henge.method", NONE)
                .start();
        return new Scope() {
            @Override
            public void resolved(String service, int version, String method) {
                observation.lowCardinalityKeyValue("henge.service", service)
                        .lowCardinalityKeyValue("henge.version", String.valueOf(version))
                        .lowCardinalityKeyValue("henge.method", method);
            }

            @Override
            public void stop(int status, String exceptionType) {
                observation.lowCardinalityKeyValue("henge.status", String.valueOf(status))
                        .lowCardinalityKeyValue("henge.exception", exceptionType == null ? NONE : exceptionType)
                        .stop();
            }
        };
    }
}
