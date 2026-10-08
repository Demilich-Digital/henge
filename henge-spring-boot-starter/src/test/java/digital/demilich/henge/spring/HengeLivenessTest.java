package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class HengeLivenessTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class, HengeAutoConfiguration.class));

    @Test
    void aBackgroundTaskThatDiesBreaksTheProcesssLiveness() {
        contextRunner.run(ctx -> {
            var availability = ctx.getBean(ApplicationAvailability.class);
            // What SpringApplication says once it has started, which a context runner doesn't.
            AvailabilityChangeEvent.publish(ctx, LivenessState.CORRECT);
            assertThat(availability.getLivenessState()).isEqualTo(LivenessState.CORRECT);

            ctx.publishEvent(new BackgroundTasks.Died("lease heartbeat", new AssertionError("fatal")));

            assertThat(availability.getLivenessState()).isEqualTo(LivenessState.BROKEN);
        });
    }
}
