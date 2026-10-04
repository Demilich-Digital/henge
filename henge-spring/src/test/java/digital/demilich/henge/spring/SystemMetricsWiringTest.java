package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** The system's meters as a plain-Spring application declares them: three beans over a {@link MeterRegistry}. */
class SystemMetricsWiringTest {

    @Configuration
    static class MetersConfig {

        @Bean
        MeterRegistry meters() {
            return new SimpleMeterRegistry();
        }

        @Bean
        MicrometerSystemMetrics systemMetrics(MeterRegistry meters) {
            return new MicrometerSystemMetrics(meters);
        }

        @Bean
        HengeHostedGauges hostedGauges(MeterRegistry meters, ApplicationContext applicationContext) {
            return new HengeHostedGauges(meters, applicationContext);
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(MetersConfig.class);
        ctx.register(configs);
        return ctx;
    }

    private static Map<String, Object> ledgerLease(int amount) {
        return Map.of("henge.leases.ledger-db.capacity", 100, "henge.leases.ledger-db.amount", amount);
    }

    @Test
    void aProcessThatHostsEverythingReportsItsLeaseItsAdvertisementsAndItsDatastoreUse() {
        MeterRegistry meters;
        try (var ctx = context(ledgerLease(40), HengeLeasesTest.LeasedConfig.class)) {
            ctx.refresh();
            meters = ctx.getBean(MeterRegistry.class);

            assertThat(meters.get("henge.lease.claims").tags("lease", "ledger-db", "outcome", "granted").counter().count()).isEqualTo(1);
            assertThat(meters.get("henge.lease.held").tag("lease", "ledger-db").gauge().value()).isEqualTo(40);
            assertThat(meters.get("henge.service.hosted").tags("service", "ledger-service", "version", "1", "mode", "embedded").gauge().value())
                    .isEqualTo(1);
            assertThat(meters.get("henge.service.hosted").tags("service", "report-service", "version", "1", "mode", "embedded").gauge().value())
                    .isEqualTo(1);
            assertThat(meters.get("henge.advertisement.renewals")
                    .tags("service", "ledger-service", "version", "1", "outcome", "success").counter().count()).isEqualTo(1);
            assertThat(meters.get("henge.store.operations").tags("purpose", "lease", "operation", "claim", "outcome", "success").timer().count())
                    .isEqualTo(1);
            assertThat(meters.get("henge.store.operations").tags("purpose", "advertisement", "operation", "put", "outcome", "success").timer().count())
                    .isPositive();
        }
        // The context is closed: the services let go, and the lease with them.
        assertThat(meters.get("henge.lease.held").tag("lease", "ledger-db").gauge().value()).isZero();
    }

    @Test
    void aLeaseRefusedHereShowsAsAnEmbeddedServiceThatIsNotHosted() {
        Map<String, Object> properties = new HashMap<>(ledgerLease(40));
        properties.put("henge.services.ledger-service.url", "http://ledger-host:8080");
        properties.put("henge.services.report-service.url", "http://report-host:8080");
        try (var ctx = context(properties, HengeLeasesTest.LeasedConfig.class, HengeLeasesTest.ForeignHolderConfig.class,
                HengeTransportConfiguration.class)) {
            ctx.refresh();
            var meters = ctx.getBean(MeterRegistry.class);

            // A refusal holds nothing, so each of the two services on the lease asks the cluster, and is refused, in turn.
            assertThat(meters.get("henge.lease.claims").tags("lease", "ledger-db", "outcome", "refused").counter().count()).isEqualTo(2);
            assertThat(meters.find("henge.lease.held").gauge()).isNull();
            // Configured embedded, so the mode says what was asked for and the 0 says it didn't happen.
            assertThat(meters.get("henge.service.hosted").tags("service", "ledger-service", "version", "1", "mode", "embedded").gauge().value())
                    .isZero();
        }
    }

    @Test
    void aServiceConfiguredRemoteIsNotHostedHere() {
        try (var ctx = context(Map.of("henge.services.echo-service.mode", "internal-rest"), EchoTestConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(MeterRegistry.class).get("henge.service.hosted")
                    .tags("service", "echo-service", "version", "1", "mode", "internal-rest").gauge().value()).isZero();
        }
    }

    @Test
    void aProcessWithoutThemReportsNothingAndStillRuns() {
        var ctx = new AnnotationConfigApplicationContext(EchoTestConfig.class);
        try {
            assertThat(ctx.getBeansOfType(MeterRegistry.class)).isEmpty();
        } finally {
            ctx.close();
        }
    }
}
