package digital.demilich.henge.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Shared construction for the Henge transport's {@link ObjectMapper} and {@link RestClient}.
 * Deliberately not exposed as Spring beans (see {@link HengeTransportConfiguration} /
 * {@link HengeDispatcherConfiguration}) so they never collide with, or cause Boot's
 * autoconfiguration to back off from, an application's own Jackson/RestClient configuration.
 * Both the client side ({@link InternalRestTransport}) and the server side
 * ({@link HengeDispatcherController}) build their mapper through this one factory method so the
 * two ends of the wire provably agree on JSON handling.
 */
final class HengeTransportSupport {

    private HengeTransportSupport() {
    }

    /**
     * Every type the processor allows across a {@code @HengeService} boundary has to survive this
     * mapper, or it works embedded and breaks only once the service is split: {@code Optional}
     * needs {@link Jdk8Module} and {@code java.time.*} needs {@link JavaTimeModule}, neither of
     * which Jackson registers by default. Dates are written as ISO-8601 strings, not numeric
     * timestamps, so the wire format is readable and independent of time-zone/precision defaults.
     *
     * <p>A zoned or offset date-time has to come back {@code equals} to what was sent, as it would
     * embedded: by default Jackson drops a {@code ZonedDateTime}'s zone region and normalizes both
     * it and an {@code OffsetDateTime} to UTC on the way in -- the same instant, but a different
     * value. So the zone id is written, and nothing is adjusted on reading.
     *
     * <p>Unknown properties are ignored so a record can gain a component without breaking calls
     * mid-rollout: a newer peer's extra component is dropped by an older one, and a component an
     * older peer doesn't send reads as {@code null}/{@code 0}. (Renaming or removing one is a
     * breaking change -- that's what a new version is for.) Only this mapper is affected; it's never
     * a bean, so the application's own Jackson configuration is untouched.
     */
    static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new HengeCollectionsModule())
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * A plain-Spring (no Boot dependency) {@link RestClient} with finite connect/read timeouts —
     * {@code RestClient.builder().build()}'s defaults are infinite, which lets one hung remote
     * service pin a caller thread forever. Timeouts come from {@code henge.transport.connect-timeout}
     * / {@code henge.transport.read-timeout} ({@link HengeProperties}).
     *
     * <p>Built by hand, and not from the application's {@code RestClient.Builder}, so that nothing the
     * application configured for its own HTTP calls (message converters, interceptors, a request factory)
     * reaches an internal call. The one thing it takes from the application is its
     * {@code observationRegistry}: each attempt is observed as {@code http.client.requests} (see
     * {@link HengeClientRequestObservationConvention}), and wherever the registry has a tracing handler the
     * trace context goes out in the request's headers, so a trace continues in the process that serves the call.
     */
    static RestClient restClient(HengeProperties properties, ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .observationRegistry(observationRegistry)
                .observationConvention(new HengeClientRequestObservationConvention())
                .build();
    }
}
