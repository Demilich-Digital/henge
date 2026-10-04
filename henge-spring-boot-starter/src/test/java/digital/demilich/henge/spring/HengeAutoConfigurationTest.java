package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The conditions {@link HengeAutoConfiguration} adds on top of the plain-Spring wiring. */
class HengeAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(HengeAutoConfiguration.class));

    @Test
    void aNonWebApplicationGetsTheClientSideWiringButNoDispatcher() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HengeAutoConfiguration.class)).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HengeDispatcherController.class);
            assertThat(ctx).hasSingleBean(digital.demilich.henge.core.ServiceTransport.class);
        });
    }

    @Test
    void dispatcherIsRegisteredByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(HengeDispatcherController.class));
    }

    @Test
    void serverEnabledFalseRemovesTheDispatcherButKeepsTheClientSideWiring() {
        runner.withPropertyValues("henge.server.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HengeDispatcherController.class);
            assertThat(ctx).hasSingleBean(digital.demilich.henge.core.ServiceTransport.class);
            assertThat(ctx).hasSingleBean(HengeProperties.class);
        });
    }

    @Test
    void explicitlyImportedPlainSpringConfigurationDoesNotCollideWithTheAutoConfiguredDispatcher() {
        runner.withUserConfiguration(EchoTestApp.class, HengeConfiguration.class)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(HengeDispatcherController.class));
    }

    @Test
    void aYamlListServeIsHonoured() {
        // henge.serve[0] is what Boot makes of a YAML list; an unknown name has to fail startup, not be ignored.
        runner.withUserConfiguration(EchoTestApp.class)
                .withPropertyValues("henge.serve[0]=no-such-service")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("henge.serve names [no-such-service]"));
    }

    @Test
    void aHigherPrecedenceYamlListServeBeatsALowerPrecedenceScalar() {
        // Boot's attached "configurationProperties" view sees both forms; the test property values
        // outrank system properties, so the list is what has to win.
        runner.withUserConfiguration(EchoTestApp.class)
                .withSystemProperties("henge.serve=echo-service")
                .withPropertyValues("henge.serve[0]=no-such-service")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("henge.serve names [no-such-service]"));
    }

    @Test
    void aMalformedPathPrefixFailsAtStartup() {
        runner.withPropertyValues("henge.server.path-prefix=_henge")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("henge.server.path-prefix '_henge' must start with '/'"));
    }

    @Test
    void withoutEnableHengeServicesTheRegistryIsAnEmptyFallback() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HengeServiceRegistry.class);
            assertThat(ctx.getBean(HengeServiceRegistry.class).find("echo-service", 1)).isEmpty();
        });
    }

    @Test
    void enableHengeServicesRegistryWinsOverTheFallback() {
        runner.withUserConfiguration(EchoTestApp.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(HengeServiceRegistry.class);
            assertThat(ctx.getBean(HengeServiceRegistry.class).find("echo-service", 1)).isPresent();
        });
    }
}
