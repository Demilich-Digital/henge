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
 * Verifies the "prune or proxy" bean-definition wiring performed by {@link ModularServiceRegistrar}:
 * embedded mode keeps the real implementation, internal-rest mode replaces it with a dynamic proxy,
 * and a misconfigured embedded service with no implementation fails startup with a clear error.
 */
class ModularServiceBeanWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EchoTestApp.class);

    @Test
    void embeddedModeKeepsRealImplementation() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EchoService.class);
            EchoService service = ctx.getBean(EchoService.class);
            assertThat(Proxy.isProxyClass(service.getClass())).isFalse();
            assertThat(service.echo("x")).isEqualTo("echo:x");
        });
    }

    @Test
    void internalRestModePrunesImplementationAndInstallsProxy() {
        contextRunner
                .withPropertyValues(
                        "modular.services.echo-service.mode=internal-rest",
                        "modular.services.echo-service.url=http://localhost:0")
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
