package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** What {@code henge.call} and {@code henge.dispatch} report, read from the observations as they stop. */
class ObservationInstrumentationTest {

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();

    private GenericApplicationContext context;
    private HengeDispatcherController controller;

    @BeforeEach
    void setUp() {
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
        context = new GenericApplicationContext();
        context.registerBean("echo-service-1", HengeServiceBindingFactoryBean.class,
                () -> new HengeServiceBindingFactoryBean(ServiceBindingSpec.embedded(EchoService.class, "echo-service", 1, EchoServiceImpl.class)));
        context.refresh();
        var serviceRegistry = new HengeServiceRegistry(
                List.of(HengeServiceDescriptor.of("echo-service", 1, EchoService.class, "echo-service-1")));
        serviceRegistry.setBeanFactory(context);
        controller = dispatcher(serviceRegistry, new MockEnvironment());
    }

    private HengeDispatcherController dispatcher(HengeServiceRegistry serviceRegistry, MockEnvironment environment) {
        return new HengeDispatcherController(serviceRegistry, HengeTransportSupport.objectMapper(),
                new HengeProperties(environment), new ObservationServiceDispatchObserver(registry));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static String tag(Observation.Context observation, String key) {
        var value = observation.getLowCardinalityKeyValue(key);
        return value == null ? null : value.getValue();
    }

    private EchoService callerOver(ServiceBinding binding) {
        return (EchoService) Proxy.newProxyInstance(EchoService.class.getClassLoader(), new Class<?>[] {EchoService.class},
                new HengeServiceInvocationHandler(binding));
    }

    private ServiceBinding embeddedWithObservation() {
        return ServiceBinding.local("echo-service", 1, new EchoServiceImpl(),
                () -> List.of(new ObservationServiceCallInterceptor(registry)));
    }

    private void dispatch(String service, int version, String method, String body, String secret) {
        controller.dispatch(service, version, method, secret, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    // -- henge.call

    @Test
    void anEmbeddedCallIsObservedWithWhatItCalledAndHowItWasFulfilled() {
        callerOver(embeddedWithObservation()).echo("x");

        assertThat(stopped).hasSize(1);
        Observation.Context call = stopped.get(0);
        assertThat(call.getName()).isEqualTo("henge.call");
        assertThat(tag(call, "henge.service")).isEqualTo("echo-service");
        assertThat(tag(call, "henge.version")).isEqualTo("1");
        assertThat(tag(call, "henge.method")).isEqualTo("echo");
        assertThat(tag(call, "henge.mode")).isEqualTo("embedded");
        assertThat(call.getError()).isNull();
    }

    @Test
    void aRemoteCallSaysItWentOverTheTransport() {
        var remote = ServiceBinding.remote("echo-service", 1, invocation -> "remote",
                () -> List.of(new ObservationServiceCallInterceptor(registry)));

        callerOver(remote).echo("x");

        assertThat(tag(stopped.get(0), "henge.mode")).isEqualTo("internal-rest");
    }

    @Test
    void aFailedCallIsObservedWithItsErrorAndStillThrows() {
        assertThatThrownBy(() -> callerOver(embeddedWithObservation()).explode("boom")).isInstanceOf(EchoFailureException.class);

        assertThat(stopped).hasSize(1);
        assertThat(stopped.get(0).getError()).isInstanceOf(EchoFailureException.class);
    }

    @Test
    void theCallIsTheCurrentObservationWhileItRunsAndNotAfter() {
        List<Observation> during = new ArrayList<>();
        var remote = ServiceBinding.remote("echo-service", 1, invocation -> {
            during.add(registry.getCurrentObservation());
            return "remote";
        }, () -> List.of(new ObservationServiceCallInterceptor(registry)));

        callerOver(remote).echo("x");

        assertThat(during).hasSize(1).doesNotContainNull();
        assertThat(registry.getCurrentObservation()).isNull();
    }

    // -- henge.dispatch

    @Test
    void aServedRequestIsObservedWithWhatItReachedAndTheStatusAnswered() {
        dispatch("echo-service", 1, "echo", "[\"x\"]", null);

        assertThat(stopped).hasSize(1);
        Observation.Context request = stopped.get(0);
        assertThat(request.getName()).isEqualTo("henge.dispatch");
        assertThat(tag(request, "henge.service")).isEqualTo("echo-service");
        assertThat(tag(request, "henge.version")).isEqualTo("1");
        assertThat(tag(request, "henge.method")).isEqualTo("echo");
        assertThat(tag(request, "henge.status")).isEqualTo("200");
        assertThat(tag(request, "henge.exception")).isEqualTo("none");
    }

    @Test
    void aBusinessExceptionIsObservedWithItsStatusAndType() {
        assertThatThrownBy(() -> dispatch("echo-service", 1, "explode", "[\"not-found\"]", null))
                .isInstanceOf(HengeDispatchException.class);
        assertThatThrownBy(() -> dispatch("echo-service", 1, "explode", "[\"boom\"]", null))
                .isInstanceOf(HengeDispatchException.class);

        assertThat(stopped).hasSize(2);
        assertThat(tag(stopped.get(0), "henge.status")).isEqualTo("404"); // @ErrorStatus(404)
        assertThat(tag(stopped.get(0), "henge.exception")).endsWith("EchoNotFoundException");
        assertThat(tag(stopped.get(1), "henge.status")).isEqualTo("500");
        assertThat(tag(stopped.get(1), "henge.exception")).endsWith("EchoFailureException");
        assertThat(tag(stopped.get(1), "henge.method")).isEqualTo("explode");
    }

    @Test
    void aRequestForSomethingNotServedNeverPutsTheCallersNamesInATag() {
        assertThatThrownBy(() -> dispatch("no-such-service", 7, "whatever", "[]", null)).isInstanceOf(HengeDispatchException.class);
        assertThatThrownBy(() -> dispatch("echo-service", 1, "no-such-method", "[]", null)).isInstanceOf(HengeDispatchException.class);
        assertThatThrownBy(() -> dispatch("echo-service", 9, "echo", "[\"x\"]", null)).isInstanceOf(HengeDispatchException.class);

        assertThat(stopped).hasSize(3).allSatisfy(request -> {
            assertThat(tag(request, "henge.status")).isEqualTo("404");
            assertThat(tag(request, "henge.service")).isEqualTo("none");
            assertThat(tag(request, "henge.version")).isEqualTo("none");
            assertThat(tag(request, "henge.method")).isEqualTo("none");
        });
    }

    @Test
    void aBadBodyIsObservedAsServedButRejected() {
        assertThatThrownBy(() -> dispatch("echo-service", 1, "echo", "not json", null)).isInstanceOf(HengeDispatchException.class);

        assertThat(tag(stopped.get(0), "henge.status")).isEqualTo("400");
        assertThat(tag(stopped.get(0), "henge.method")).isEqualTo("echo");
    }

    @Test
    void aRejectedSecretIsObservedBeforeAnythingIsResolved() {
        var serviceRegistry = new HengeServiceRegistry(
                List.of(HengeServiceDescriptor.of("echo-service", 1, EchoService.class, "echo-service-1")));
        serviceRegistry.setBeanFactory(context);
        controller = dispatcher(serviceRegistry, new MockEnvironment().withProperty("henge.transport.secret", "s3cret"));

        assertThatThrownBy(() -> dispatch("echo-service", 1, "echo", "[\"x\"]", "wrong")).isInstanceOf(HengeDispatchException.class);

        assertThat(tag(stopped.get(0), "henge.status")).isEqualTo("403");
        assertThat(tag(stopped.get(0), "henge.service")).isEqualTo("none");
    }
}
