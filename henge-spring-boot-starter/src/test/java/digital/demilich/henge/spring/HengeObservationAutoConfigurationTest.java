package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Calls and dispatches are observed when the application has an {@link ObservationRegistry}, and not otherwise. */
class HengeObservationAutoConfigurationTest {

    /** An {@link ObservationRegistry} that keeps every observation it saw stop. */
    private static final class Recorder {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        final ObservationRegistry registry = ObservationRegistry.create();

        Recorder() {
            registry.observationConfig().observationHandler(new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                    stopped.add(context);
                }
            });
        }

        List<Observation.Context> named(String name) {
            return stopped.stream().filter(context -> context.getName().equals(name)).toList();
        }
    }

    private static String tag(Observation.Context observation, String key) {
        return observation.getLowCardinalityKeyValue(key).getValue();
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(EchoTestApp.class);

    @Test
    void nothingIsObservedWithoutAnObservationRegistry() {
        contextRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ObservationServiceCallInterceptor.class);
            assertThat(ctx).doesNotHaveBean(ObservationServiceDispatchObserver.class);
            assertThat(ctx.getBean(EchoService.class).echo("x")).isEqualTo("echo:x");
        });
    }

    @Test
    void anObservationRegistryTurnsOnTheObservationOfCallsAndDispatches() {
        var recorder = new Recorder();

        contextRunner.withBean(ObservationRegistry.class, () -> recorder.registry).run(ctx -> {
            assertThat(ctx).hasSingleBean(ObservationServiceCallInterceptor.class);
            assertThat(ctx).hasSingleBean(ObservationServiceDispatchObserver.class);

            ctx.getBean(EchoService.class).echo("x");

            assertThat(recorder.named("henge.call")).hasSize(1);
            assertThat(tag(recorder.named("henge.call").get(0), "henge.mode")).isEqualTo("embedded");
        });
    }

    @Test
    void anApplicationsOwnInterceptorOfTheTypeIsUsedInsteadOfOurs() {
        var recorder = new Recorder();
        var own = new ObservationServiceCallInterceptor(recorder.registry);

        contextRunner.withBean(ObservationRegistry.class, () -> recorder.registry)
                .withBean(ObservationServiceCallInterceptor.class, () -> own)
                .run(ctx -> assertThat(ctx.getBean(ObservationServiceCallInterceptor.class)).isSameAs(own));
    }

    @Test
    void aCallOverHttpIsObservedByTheCallerAndByTheServer() {
        var serverSide = new Recorder();
        var clientSide = new Recorder();
        ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(ObservationRegistry.class, () -> serverSide.registry))
                .run();
        try {
            int serverPort = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "henge.server.enabled=false",
                            "henge.services.echo-service.mode=internal-rest",
                            "henge.services.echo-service.url=http://localhost:" + serverPort)
                    .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(ObservationRegistry.class, () -> clientSide.registry))
                    .run();
            try {
                assertThat(client.getBean(EchoService.class).echo("hi")).isEqualTo("echo:hi");

                assertThat(clientSide.named("henge.call")).hasSize(1);
                assertThat(tag(clientSide.named("henge.call").get(0), "henge.mode")).isEqualTo("internal-rest");
                assertThat(clientSide.named("henge.dispatch")).isEmpty(); // it serves nothing

                assertThat(serverSide.named("henge.dispatch")).hasSize(1);
                assertThat(tag(serverSide.named("henge.dispatch").get(0), "henge.status")).isEqualTo("200");
                assertThat(tag(serverSide.named("henge.dispatch").get(0), "henge.method")).isEqualTo("echo");
                // The server's own callers are not involved: the call it was handed was already observed on the client.
                assertThat(serverSide.named("henge.call")).isEmpty();
            } finally {
                client.close();
            }
        } finally {
            server.close();
        }
    }
}
