package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Plain Spring, no Boot: the topology controller exists only where it was asked for. */
class ModularTopologyConfigurationTest {

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(EchoTestConfig.class, ModularConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void itIsNotThereByDefault() {
        try (var ctx = context(Map.of())) {
            assertThat(ctx.getBeansOfType(ModularTopologyController.class)).isEmpty();
        }
    }

    @Test
    void itIsThereWhenEnabled() {
        try (var ctx = context(Map.of("modular.topology.enabled", "true"))) {
            assertThat(ctx.getBeansOfType(ModularTopologyController.class)).hasSize(1);
        }
    }
}
