package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** {@code henge.recent-versions}: of three implementations on the classpath, only the newest few are run. */
class HengeRecentVersionsTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.windowed")
    static class WindowedConfig {
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(WindowedConfig.class);
        return ctx;
    }

    @Test
    void byDefaultTheTwoNewestVersionsRun() {
        try (var ctx = context(Map.of())) {
            ctx.refresh();
            assertThat(ctx.getBeansOfType(HengeServiceRegistry.class).values().iterator().next().hosted())
                    .extracting(HengeServiceDescriptor::version)
                    .containsExactlyInAnyOrder(2, 3);
        }
    }

    @Test
    void raisingItRunsOlderVersionsToo() {
        try (var ctx = context(Map.of("henge.recent-versions", 3))) {
            ctx.refresh();
            assertThat(ctx.getBeansOfType(HengeServiceRegistry.class).values().iterator().next().hosted())
                    .extracting(HengeServiceDescriptor::version)
                    .containsExactlyInAnyOrder(1, 2, 3);
        }
    }

    @Test
    void aVersionNamedOutsideTheWindowFailsStartup() {
        try (var ctx = context(Map.of("henge.serve", "windowed-service@1"))) {
            assertThatThrownBy(ctx::refresh).hasMessageContaining("henge.recent-versions");
        }
    }
}
