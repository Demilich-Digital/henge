package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceTransport;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import io.micrometer.observation.ObservationRegistry;
import java.time.InstantSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Plain-Spring core wiring: everything needed to <em>call</em> Henge services (embedded or
 * remote) from this process, independent of whether it also <em>serves</em> any of them — see
 * {@link HengeDispatcherConfiguration} for that, or {@link HengeConfiguration} to import both
 * at once. Combined with {@link EnableHengeServices} on your own {@code @Configuration} class,
 * this is the plain-Spring equivalent of {@code henge-spring-boot-starter}'s autoconfiguration.
 *
 * <p>Unlike the Boot starter, this deliberately does <em>not</em> provide a fallback empty
 * {@link HengeServiceRegistry} bean — always pair this with {@link EnableHengeServices} on
 * your own {@code @Configuration} class, which registers the real one. Omitting it fails clearly
 * at startup ("no bean of type HengeServiceRegistry") rather than silently doing nothing.
 *
 * <p>The transport's {@code ObjectMapper}/{@code RestClient} are deliberately <em>not</em>
 * {@code @Bean}s (see {@link HengeTransportSupport}) — publishing an unqualified
 * {@code ObjectMapper} bean here could make Boot's {@code JacksonAutoConfiguration} back off from
 * the application's own {@code spring.jackson.*} configuration, and would risk ambiguity against a
 * user-defined {@code ObjectMapper}/{@code RestClient} bean.
 */
@Configuration
public class HengeTransportConfiguration {

    @Bean
    public HengeProperties hengeProperties(Environment environment) {
        return new HengeProperties(environment);
    }

    /**
     * Where a service with no configured url is looked up: among the processes that advertise it on
     * the datastore, refreshed as often as they heartbeat. Without a datastore bean (a process that
     * doesn't use {@link EnableHengeServices}) there is nothing to look in.
     */
    @Bean
    public ServiceTransport internalRestTransport(HengeProperties hengeProperties,
            ObjectProvider<SystemEphemeralDatastore> datastore, ObjectProvider<SystemMetrics> systemMetrics,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        SystemMetrics metrics = systemMetrics.getIfAvailable(() -> SystemMetrics.NONE);
        SystemEphemeralDatastore available = datastore.getIfAvailable();
        AdvertisedEndpoints advertised = available == null ? null
                : new AdvertisedEndpoints(metrics.measured(available, "routing"), HengeLeaseKeeper.DEFAULT_TTL.dividedBy(3),
                        InstantSource.system(), metrics);
        return new InternalRestTransport(
                HengeTransportSupport.restClient(hengeProperties, observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP)),
                HengeTransportSupport.objectMapper(), hengeProperties,
                advertised, metrics);
    }
}
