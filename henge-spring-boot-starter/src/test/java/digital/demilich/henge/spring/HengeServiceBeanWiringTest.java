package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import digital.demilich.henge.spring.fixture.orphan.OrphanTestApp;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the "prune or proxy" bean-definition wiring performed by {@link HengeServiceRegistrar}:
 * every injection point gets a dynamic proxy; embedded mode calls the real implementation behind it,
 * internal-rest mode leaves none, and a misconfigured embedded service with no implementation fails
 * startup with a clear error.
 */
class HengeServiceBeanWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EchoTestApp.class);

    @Test
    void embeddedModeInjectsTheProxyAndKeepsTheImplementationHidden() {
        contextRunner.run(ctx -> {
            // What a caller gets by type is the proxy: the implementation is a hidden bean, not an autowire candidate.
            EchoService service = ctx.getBean(EchoService.class);
            assertThat(Proxy.isProxyClass(service.getClass())).isTrue();
            assertThat(ctx.getBean("echo-service-1.impl")).isInstanceOf(EchoServiceImpl.class).isNotSameAs(service);
            assertThat(service.echo("x")).isEqualTo("echo:x");
        });
    }

    @Test
    void internalRestModePrunesImplementationAndInstallsProxy() {
        contextRunner
                .withPropertyValues(
                        "henge.services.echo-service.mode=internal-rest",
                        "henge.services.echo-service.url=http://localhost:0")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EchoService.class);
                    EchoService service = ctx.getBean(EchoService.class);
                    assertThat(Proxy.isProxyClass(service.getClass())).isTrue();
                    assertThat(ctx.getBeansOfType(EchoServiceImpl.class)).isEmpty();
                });
    }

    @Test
    void embeddedModeWithoutImplementationFailsFast() {
        new ApplicationContextRunner().withUserConfiguration(OrphanTestApp.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable failure = ctx.getStartupFailure();
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root).hasMessageContaining("orphan-service");
        });
    }
}
