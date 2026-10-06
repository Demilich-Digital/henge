package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoTestApp;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * A trace that starts in the process that makes a call continues in the process that serves it, with a
 * real tracer (Brave) and real header propagation over real HTTP. Wired the way Boot's tracing
 * auto-configuration wires an application, by hand here so Actuator isn't needed on the test classpath.
 */
class HengeTracePropagationTest {

    /** One process's tracing: a registry whose observations are Brave spans, which are kept as they finish. */
    private static final class Tracer {
        final List<MutableSpan> finished = new CopyOnWriteArrayList<>();
        final ObservationRegistry registry = ObservationRegistry.create();

        Tracer(String serviceName) {
            SpanHandler keep = new SpanHandler() {
                @Override
                public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                    finished.add(span);
                    return true;
                }
            };
            Tracing tracing = Tracing.newBuilder().localServiceName(serviceName).sampler(Sampler.ALWAYS_SAMPLE).addSpanHandler(keep).build();
            var tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()), new BraveBaggageManager());
            var propagator = new BravePropagator(tracing);
            registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                    new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                    new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                    new DefaultTracingObservationHandler(tracer)));
        }

        List<MutableSpan> named(String name) {
            return finished.stream().filter(span -> span.name() != null && span.name().startsWith(name)).toList();
        }

        MutableSpan only(String name) {
            assertThat(named(name)).as("spans named %s in %s", name, finished.stream().map(MutableSpan::name).toList()).hasSize(1);
            return named(name).get(0);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static ConfigurableApplicationContext server(Tracer tracer) {
        return new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .initializers(ctx -> {
                    var context = (GenericApplicationContext) ctx;
                    context.registerBean(ObservationRegistry.class, () -> tracer.registry);
                    // What Boot's Actuator registers for Spring MVC: it continues a trace found in the request's headers.
                    context.registerBean("serverObservationFilter", FilterRegistrationBean.class,
                            () -> new FilterRegistrationBean<>(new ServerHttpObservationFilter(tracer.registry)));
                })
                .run();
    }

    private static ConfigurableApplicationContext client(Tracer tracer, String url, String... more) {
        var builder = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "henge.server.enabled=false",
                        "henge.store.type=in-process", "henge.services.echo-service.mode=internal-rest", "henge.services.echo-service.url=" + url)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(ObservationRegistry.class, () -> tracer.registry));
        if (more.length > 0) {
            builder.properties(more);
        }
        return builder.run();
    }

    @Test
    void aTraceStartedByTheCallerContinuesInTheProcessThatServesTheCall() throws Exception {
        var serverSide = new Tracer("host");
        var clientSide = new Tracer("caller");
        try (ConfigurableApplicationContext server = server(serverSide)) {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            try (ConfigurableApplicationContext client = client(clientSide, "http://localhost:" + port)) {
                assertThat(client.getBean(EchoService.class).echo("hi")).isEqualTo("echo:hi");

                // The host's request span ends after the response is sent, so it may land just after the call returns.
                awaitTrue(() -> !serverSide.named("henge.dispatch").isEmpty() && !serverSide.named("http post").isEmpty());

                MutableSpan call = clientSide.only("henge.call");
                MutableSpan attempt = clientSide.only("http post");
                MutableSpan hostRequest = serverSide.only("http post");
                MutableSpan dispatch = serverSide.only("henge.dispatch");

                // The caller's side: the call, and the one HTTP attempt inside it.
                assertThat(call.parentId()).isNull();
                assertThat(attempt.parentId()).isEqualTo(call.id());
                assertThat(attempt.traceId()).isEqualTo(call.traceId());

                // The host's side: one trace, the caller's, all the way in to the dispatch.
                assertThat(hostRequest.traceId()).isEqualTo(call.traceId());
                assertThat(dispatch.traceId()).isEqualTo(call.traceId());
                assertThat(dispatch.parentId()).isEqualTo(hostRequest.id());
                // ... and the host's request is what the caller's HTTP attempt sent, not a stranger's.
                assertThat(hostRequest.parentId()).isEqualTo(attempt.id());
            }
        }
    }

    @Test
    void everyAttemptOfARetriedCallIsItsOwnSpanUnderTheCall() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var clientSide = new Tracer("caller");
        try (ConfigurableApplicationContext client = client(clientSide, "http://localhost:" + closedPort,
                "henge.transport.retry.max-attempts=3", "henge.transport.retry.backoff=0")) {
            assertThatThrownBy(() -> client.getBean(EchoService.class).echo("hi")).isInstanceOf(RuntimeException.class);

            MutableSpan call = clientSide.only("henge.call");
            List<MutableSpan> attempts = clientSide.named("http post");
            assertThat(attempts).hasSize(3).allSatisfy(attempt -> {
                assertThat(attempt.parentId()).isEqualTo(call.id());
                assertThat(attempt.traceId()).isEqualTo(call.traceId());
            });
        }
    }

    @Test
    void aProcessWithoutTracingStillMakesTheCall() throws Exception {
        try (ConfigurableApplicationContext server = new SpringApplicationBuilder(EchoTestApp.class)
                .web(WebApplicationType.SERVLET).properties("server.port=0", "spring.main.banner-mode=off").run()) {
            int port = ((ServletWebServerApplicationContext) server).getWebServer().getPort();
            try (ConfigurableApplicationContext client = new SpringApplicationBuilder(EchoTestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "henge.server.enabled=false",
                            "henge.store.type=in-process", "henge.services.echo-service.mode=internal-rest",
                            "henge.services.echo-service.url=http://localhost:" + port)
                    .run()) {
                assertThat(client.getBean(EchoService.class).echo("hi")).isEqualTo("echo:hi");
            }
        }
    }
}
