package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The conditions {@link ModularAutoConfiguration} adds on top of the plain-Spring wiring. */
class ModularAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModularAutoConfiguration.class));

    @Test
    void dispatcherIsRegisteredByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ModularDispatcherController.class));
    }

    @Test
    void serverEnabledFalseRemovesTheDispatcherButKeepsTheClientSideWiring() {
        runner.withPropertyValues("modular.server.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ModularDispatcherController.class);
            assertThat(ctx).hasSingleBean(digital.demilich.henge.core.ServiceTransport.class);
            assertThat(ctx).hasSingleBean(ModularProperties.class);
        });
    }

    @Test
    void explicitlyImportedPlainSpringConfigurationDoesNotCollideWithTheAutoConfiguredDispatcher() {
        runner.withUserConfiguration(EchoTestApp.class, ModularConfiguration.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ModularDispatcherController.class));
    }

    @Test
    void withoutEnableModularServicesTheRegistryIsAnEmptyFallback() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ModularServiceRegistry.class);
            assertThat(ctx.getBean(ModularServiceRegistry.class).find("echo-service", 1)).isEmpty();
        });
    }

    @Test
    void enableModularServicesRegistryWinsOverTheFallback() {
        runner.withUserConfiguration(EchoTestApp.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(ModularServiceRegistry.class);
            assertThat(ctx.getBean(ModularServiceRegistry.class).find("echo-service", 1)).isPresent();
        });
    }
}
