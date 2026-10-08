package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimited;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.ratelimitfixture.SendService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class HengeRateLimitsTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.ratelimitfixture")
    static class SendConfig {
    }

    /** Not a Henge service: a limiter is an ordinary bean, for a field or a factory method alike. */
    static class Gate {

        @Autowired
        @RateLimited("api")
        RateLimiter byField;

        final RateLimiter byParameter;

        Gate(RateLimiter byParameter) {
            this.byParameter = byParameter;
        }
    }

    @Configuration
    static class GateConfig {

        @Bean
        Gate gate(@RateLimited("api") RateLimiter limiter) {
            return new Gate(limiter);
        }
    }

    @Configuration
    static class MetricsConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        MicrometerSystemMetrics systemMetrics(MeterRegistry registry) {
            return new MicrometerSystemMetrics(registry);
        }
    }

    /** Stands in for a datastore every node shares, such as Redis. */
    static final SystemEphemeralDatastore SHARED = new InProcessEphemeralDatastore();

    @Configuration
    static class SharedStoreConfig {

        @Bean
        SystemEphemeralDatastore datastore() {
            return SHARED;
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(configs);
        return ctx;
    }

    /** {@code name} lets {@code capacity} through, then nothing for an hour, so nothing leaks back mid-test. */
    private static Map<String, Object> limit(String name, int capacity) {
        return Map.of(
                "henge.rate-limits." + name + ".permits", 1,
                "henge.rate-limits." + name + ".period", "1h",
                "henge.rate-limits." + name + ".capacity", capacity);
    }

    private static Map<String, Object> merged(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> all = new HashMap<>(first);
        all.putAll(second);
        return all;
    }

    @Test
    void aServiceTakesItsConfiguredLimiterAndEachSubjectHasItsOwnBucket() {
        try (var ctx = context(limit("sends", 2), SendConfig.class)) {
            ctx.refresh();
            SendService sends = ctx.getBean(SendService.class);

            assertThat(sends.send("alice")).isTrue();
            assertThat(sends.send("alice")).isTrue();
            assertThat(sends.send("alice")).isFalse();
            assertThat(sends.send("bob")).isTrue();
        }
    }

    @Test
    void anyBeanCanTakeALimiterAndEveryInjectionOfANameDrawsOnOneBucket() {
        try (var ctx = context(merged(limit("sends", 1), limit("api", 2)), SendConfig.class, GateConfig.class)) {
            ctx.refresh();
            Gate gate = ctx.getBean(Gate.class);

            assertThat(gate.byField.tryAcquire().granted()).isTrue();
            assertThat(gate.byParameter.tryAcquire().granted()).isTrue();
            assertThat(gate.byField.tryAcquire().granted()).isFalse();

            // Another name is another bucket.
            assertThat(ctx.getBean(SendService.class).send("alice")).isTrue();
        }
    }

    @Test
    void nodesSharingADatastoreShareTheLimit() {
        try (var first = context(limit("sends", 3), SendConfig.class, SharedStoreConfig.class);
                var second = context(limit("sends", 3), SendConfig.class, SharedStoreConfig.class)) {
            first.refresh();
            second.refresh();

            assertThat(first.getBean(SendService.class).send("carol")).isTrue();
            assertThat(second.getBean(SendService.class).send("carol")).isTrue();
            assertThat(first.getBean(SendService.class).send("carol")).isTrue();
            assertThat(second.getBean(SendService.class).send("carol")).isFalse();
        }
    }

    @Test
    void theCapacityDefaultsToOnePeriodsPermits() {
        try (var ctx = context(Map.of("henge.rate-limits.sends.permits", 2, "henge.rate-limits.sends.period", "1h"),
                SendConfig.class)) {
            ctx.refresh();
            SendService sends = ctx.getBean(SendService.class);

            assertThat(sends.send("dave")).isTrue();
            assertThat(sends.send("dave")).isTrue();
            assertThat(sends.send("dave")).isFalse();
        }
    }

    @Test
    void aLimitCanBeConfiguredPurelyFromEnvironmentVariables() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of(
                "HENGE_RATE_LIMITS_SENDS_PERMITS", "1",
                "HENGE_RATE_LIMITS_SENDS_PERIOD", "1h")));
        ctx.register(SendConfig.class);
        try (ctx) {
            ctx.refresh();
            SendService sends = ctx.getBean(SendService.class);

            assertThat(sends.send("erin")).isTrue();
            assertThat(sends.send("erin")).isFalse();
        }
    }

    @Test
    void grantsAndRefusalsAreCountedByLimitAndTheStoreTimedAsRateLimiting() {
        try (var ctx = context(limit("sends", 1), SendConfig.class, MetricsConfig.class)) {
            ctx.refresh();
            SendService sends = ctx.getBean(SendService.class);
            sends.send("frank");
            sends.send("frank");
            sends.send("grace");

            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.get("henge.rate-limit.acquisitions").tags("limit", "sends", "outcome", "granted").counter().count())
                    .isEqualTo(2);
            assertThat(registry.get("henge.rate-limit.acquisitions").tags("limit", "sends", "outcome", "refused").counter().count())
                    .isEqualTo(1);
            assertThat(registry.get(MeteredDatastore.NAME).tags("purpose", "rate-limit", "operation", "tryAcquire").timer().count())
                    .isEqualTo(3);
        }
    }

    @Test
    void aServiceAskingForALimitNobodyConfiguredFailsStartupNamingTheProperties() {
        try (var ctx = context(Map.of(), SendConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasMessageContaining("SendServiceImpl takes @RateLimited(\"sends\")")
                    .hasMessageContaining("henge.rate-limits.sends.permits and henge.rate-limits.sends.period");
        }
    }

    @Test
    void anUnknownKeyFailsStartup() {
        try (var ctx = context(merged(limit("sends", 1), Map.of("henge.rate-limits.sends.burst", 5)), SendConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasMessageContaining("henge.rate-limits.sends.burst: not a known key (permits, period, capacity)");
        }
    }

    @Test
    void aNameThatIsNotKebabCaseFailsStartup() {
        try (var ctx = context(merged(limit("sends", 1), limit("Sends_Fast", 1)), SendConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasMessageContaining("henge.rate-limits.Sends_Fast.permits: a rate limit's name must be lowercase kebab case");
        }
    }

    @Test
    void aLimitWithoutItsPeriodFailsStartup() {
        try (var ctx = context(Map.of("henge.rate-limits.sends.permits", 5), SendConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasMessageContaining("henge.rate-limits.sends.permits and henge.rate-limits.sends.period are both required");
        }
    }

    @Test
    void aPeriodOutsideWhatTheStoreCanCountExactlyFailsStartup() {
        try (var ctx = context(Map.of("henge.rate-limits.sends.permits", 5, "henge.rate-limits.sends.period", "2h"),
                SendConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasMessageContaining("henge.rate-limits.sends.period=2h: period must be between 1ms and 1h");
        }
    }
}
