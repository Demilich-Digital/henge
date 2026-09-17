package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.counter.CounterService;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV1;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV2;
import digital.demilich.henge.spring.fixture.counter.CounterTestConfig;
import digital.demilich.henge.spring.fixture.counter.DefaultConsumer;
import digital.demilich.henge.spring.fixture.counter.PinnedConsumer;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Proves the core wiring mechanism — default-version resolution via {@code @Primary}, explicit
 * version pinning via {@code @ServiceVersion} qualifiers, and {@code --modular.serve}-driven mode
 * selection — works with a plain {@link AnnotationConfigApplicationContext}, no Spring Boot
 * anywhere. Mirrors {@code modular-spring-boot-starter}'s {@code ModularServiceMultiVersionTest}
 * scenarios, minus the Boot bootstrap.
 */
class ModularServiceRegistrarPlainSpringTest {

    @Test
    void unqualifiedDependencyResolvesToDefaultVersion() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            DefaultConsumer consumer = ctx.getBean(DefaultConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v1");
        }
    }

    @Test
    void qualifiedDependencyResolvesToPinnedVersion() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            PinnedConsumer consumer = ctx.getBean(PinnedConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v2");
        }
    }

    @Test
    void bothVersionsAreRegisteredAsDistinctBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            assertThat(ctx.getBeansOfType(CounterService.class)).hasSize(2);
            assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
            assertThat(ctx.getBean(CounterServiceV2.class)).isNotNull();
        }
    }

    @Test
    void modularServePropertyOverridesLocalImplDefaultToRemote() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.serve", "counter-service@1")));
        ctx.register(CounterTestConfig.class, ModularTransportConfiguration.class);
        ctx.refresh();
        try {
            // v1 is served here -> real bean, unaffected.
            assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
            // v2 has a local impl too, but isn't listed in modular.serve -> proxy instead.
            assertThat(ctx.getBeanProvider(CounterServiceV2.class).getIfAvailable()).isNull();

            boolean anyProxy = ctx.getBeansOfType(CounterService.class).values().stream()
                    .anyMatch(bean -> Proxy.isProxyClass(bean.getClass()));
            assertThat(anyProxy).isTrue();
        } finally {
            ctx.close();
        }
    }
}
