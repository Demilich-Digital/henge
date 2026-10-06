package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.duplicateinterfacename.DuplicateInterfaceNameTestApp;
import digital.demilich.henge.spring.fixture.multiversion.CounterService;
import digital.demilich.henge.spring.fixture.multiversion.CounterServiceV1;
import digital.demilich.henge.spring.fixture.multiversion.CounterServiceV2;
import digital.demilich.henge.spring.fixture.multiversion.DefaultConsumer;
import digital.demilich.henge.spring.fixture.multiversion.MultiVersionTestApp;
import digital.demilich.henge.spring.fixture.multiversion.PinnedConsumer;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies that multiple {@code @ServiceVersion} implementations of the same interface can
 * coexist, that an unqualified dependency resolves to the interface's default version via
 * {@code @Primary}, and that an explicitly {@code @ServiceVersion}-qualified dependency pins to
 * whichever version it names — including when that version is remote while the default stays
 * embedded in the same process.
 */
class HengeServiceMultiVersionTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(MultiVersionTestApp.class);

    @Test
    void unqualifiedDependencyResolvesToDefaultVersion() {
        contextRunner.run(ctx -> {
            DefaultConsumer consumer = ctx.getBean(DefaultConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v1");
        });
    }

    @Test
    void qualifiedDependencyResolvesToPinnedVersion() {
        contextRunner.run(ctx -> {
            PinnedConsumer consumer = ctx.getBean(PinnedConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v2");
        });
    }

    @Test
    void bothVersionsAreRegisteredAsDistinctBeans() {
        contextRunner.run(ctx -> {
            assertThat(ctx.getBeansOfType(CounterService.class).values())
                    .filteredOn(bean -> Proxy.isProxyClass(bean.getClass())).hasSize(2);
            assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
            assertThat(ctx.getBean(CounterServiceV2.class)).isNotNull();
        });
    }

    @Test
    void nonDefaultVersionCanBeRemoteWhileDefaultStaysEmbedded() {
        contextRunner
                .withPropertyValues(
                        "henge.store.type=in-process", "henge.services.counter-service.versions.2.mode=internal-rest",
                        "henge.services.counter-service.versions.2.url=http://localhost:0")
                .run(ctx -> {
                    // v1 (default) is still the real local bean.
                    assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
                    // v2 is no longer registered under its concrete impl class at all -- it's a proxy now.
                    assertThat(ctx.getBeanProvider(CounterServiceV2.class).getIfAvailable()).isNull();

                    boolean anyProxy = ctx.getBeansOfType(CounterService.class).values().stream()
                            .anyMatch(bean -> Proxy.isProxyClass(bean.getClass()));
                    assertThat(anyProxy).isTrue();
                });
    }

    @Test
    void serveSpecOverridesLocalImplDefaultToRemote() {
        contextRunner
                .withPropertyValues("henge.store.type=in-process", "henge.serve=counter-service@1")
                .run(ctx -> {
                    // v1 is served here -> real bean, unaffected.
                    assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
                    // v2 has a local impl too, but isn't listed in --henge.serve -> proxy instead,
                    // proving --henge.serve overrides the local-impl-implies-embedded default.
                    assertThat(ctx.getBeanProvider(CounterServiceV2.class).getIfAvailable()).isNull();

                    boolean anyProxy = ctx.getBeansOfType(CounterService.class).values().stream()
                            .anyMatch(bean -> Proxy.isProxyClass(bean.getClass()));
                    assertThat(anyProxy).isTrue();
                });
    }

    @Test
    void serveSpecContradictingExplicitInternalRestModeFailsFast() {
        contextRunner
                .withPropertyValues(
                        "henge.serve=counter-service@2",
                        "henge.store.type=in-process", "henge.services.counter-service.versions.2.mode=internal-rest",
                        "henge.services.counter-service.versions.2.url=http://localhost:0")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = ctx.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root).hasMessageContaining("counter-service@2").hasMessageContaining("--henge.serve");
                });
    }

    @Test
    void duplicateInterfaceSimpleNameFailsFast() {
        new ApplicationContextRunner().withUserConfiguration(DuplicateInterfaceNameTestApp.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable root = ctx.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root)
                    .hasMessageContaining("widget-service")
                    .hasMessageContaining("pkga.WidgetService")
                    .hasMessageContaining("pkgb.WidgetService")
                    .hasMessageContaining("@HengeService(name = ...)");
        });
    }
}
