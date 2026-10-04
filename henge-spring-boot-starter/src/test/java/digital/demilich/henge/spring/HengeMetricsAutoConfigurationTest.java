package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The system's meters are reported when the application has a {@link MeterRegistry}, and not otherwise. */
class HengeMetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(EchoTestApp.class);

    @Test
    void nothingIsReportedWithoutAMeterRegistry() {
        contextRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MicrometerSystemMetrics.class);
            assertThat(ctx).doesNotHaveBean(HengeHostedGauges.class);
            assertThat(ctx).hasNotFailed();
        });
    }

    @Test
    void aMeterRegistryTurnsOnTheSystemsMeters() {
        var meters = new SimpleMeterRegistry();

        contextRunner.withBean(MeterRegistry.class, () -> meters).run(ctx -> {
            assertThat(ctx).hasSingleBean(MicrometerSystemMetrics.class);
            assertThat(ctx).hasSingleBean(HengeHostedGauges.class);

            assertThat(meters.get("henge.service.hosted").tags("service", "echo-service", "version", "1", "mode", "embedded").gauge().value())
                    .isEqualTo(1);
            // The advertiser is built by the registrar, not as a bean method: it still gets the metrics.
            assertThat(meters.get("henge.advertisement.renewals")
                    .tags("service", "echo-service", "version", "1", "outcome", "success").counter().count()).isEqualTo(1);
            assertThat(meters.get("henge.store.operations")
                    .tags("purpose", "advertisement", "operation", "put", "outcome", "success").timer().count()).isPositive();
        });
    }

    @Test
    void anApplicationsOwnSystemMetricsAreUsedInsteadOfOurs() {
        var meters = new SimpleMeterRegistry();
        var own = new MicrometerSystemMetrics(meters);

        contextRunner.withBean(MeterRegistry.class, () -> meters).withBean(MicrometerSystemMetrics.class, () -> own)
                .run(ctx -> assertThat(ctx.getBean(MicrometerSystemMetrics.class)).isSameAs(own));
    }
}
