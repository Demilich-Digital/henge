package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The conditions {@link ModularAutoConfiguration} adds on top of the plain-Spring wiring. */
class ModularAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModularAutoConfiguration.class));

    @Test
    void aNonWebApplicationGetsTheClientSideWiringButNoDispatcher() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModularAutoConfiguration.class)).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ModularDispatcherController.class);
            assertThat(ctx).hasSingleBean(digital.demilich.henge.core.ServiceTransport.class);
        });
    }

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
    void aYamlListServeIsHonoured() {
        // modular.serve[0] is what Boot makes of a YAML list; an unknown name has to fail startup, not be ignored.
        runner.withUserConfiguration(EchoTestApp.class)
                .withPropertyValues("modular.serve[0]=no-such-service")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("modular.serve names [no-such-service]"));
    }

    @Test
    void aHigherPrecedenceYamlListServeBeatsALowerPrecedenceScalar() {
        // Boot's attached "configurationProperties" view sees both forms; the test property values
        // outrank system properties, so the list is what has to win.
        runner.withUserConfiguration(EchoTestApp.class)
                .withSystemProperties("modular.serve=echo-service")
                .withPropertyValues("modular.serve[0]=no-such-service")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("modular.serve names [no-such-service]"));
    }

    @Test
    void aMalformedPathPrefixFailsAtStartup() {
        runner.withPropertyValues("modular.server.path-prefix=_modular")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("modular.server.path-prefix '_modular' must start with '/'"));
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
