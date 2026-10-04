package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Plain Spring, no Boot: the topology controller exists only where it was asked for. */
class HengeTopologyConfigurationTest {

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(EchoTestConfig.class, HengeConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void itIsNotThereByDefault() {
        try (var ctx = context(Map.of())) {
            assertThat(ctx.getBeansOfType(HengeTopologyController.class)).isEmpty();
        }
    }

    @Test
    void itIsThereWhenEnabled() {
        try (var ctx = context(Map.of("henge.topology.enabled", "true"))) {
            assertThat(ctx.getBeansOfType(HengeTopologyController.class)).hasSize(1);
        }
    }
}
