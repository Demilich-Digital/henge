package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ServiceBindingTest {

    private final EchoServiceImpl implementation = new EchoServiceImpl();
    private final List<String> events = new ArrayList<>();

    private static EchoService proxyOver(ServiceBinding binding) {
        return (EchoService) Proxy.newProxyInstance(EchoService.class.getClassLoader(), new Class<?>[] {EchoService.class},
                new ModularServiceInvocationHandler(binding));
    }

    private ServiceBinding local(ServiceCallInterceptor... interceptors) {
        return ServiceBinding.local("echo-service", 1, implementation, () -> List.of(interceptors));
    }

    @Test
    void aCallReachesTheImplementation() {
        assertThat(proxyOver(local()).echo("x")).isEqualTo("echo:x");
        assertThat(implementation.getCallCount()).isEqualTo(1);
    }

    @Test
    void interceptorsRunOutermostFirstAroundTheTarget() {
        ServiceCallInterceptor outer = (invocation, chain) -> {
            events.add("outer in " + invocation.methodName());
            try {
                return chain.proceed();
            } finally {
                events.add("outer out");
            }
        };
        ServiceCallInterceptor inner = (invocation, chain) -> {
            events.add("inner in");
            Object result = chain.proceed();
            events.add("inner out " + result);
            return result;
        };

        proxyOver(local(outer, inner)).echo("x");

        assertThat(events).containsExactly("outer in echo", "inner in", "inner out echo:x", "outer out");
    }

    @Test
    void anInterceptorSeesHowTheCallIsFulfilled() {
        List<ModularMode> modes = new ArrayList<>();
        ServiceCallInterceptor record = (invocation, chain) -> {
            modes.add(chain.mode());
            return chain.proceed();
        };

        proxyOver(local(record)).echo("x");
        proxyOver(ServiceBinding.remote("echo-service", 1, invocation -> "remote", () -> List.of(record))).echo("x");

        assertThat(modes).containsExactly(ModularMode.EMBEDDED, ModularMode.INTERNAL_REST);
    }

    @Test
    void anInterceptorMayProceedAgainToRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ServiceCallInterceptor retryOnce = (invocation, chain) -> {
            try {
                return chain.proceed();
            } catch (EchoFailureException e) {
                attempts.incrementAndGet();
                return chain.proceed();
            }
        };

        assertThatThrownBy(() -> proxyOver(local(retryOnce)).explode("boom")).isInstanceOf(EchoFailureException.class);

        assertThat(attempts).hasValue(1);
        assertThat(implementation.getExplodeCount()).isEqualTo(2);
    }

    @Test
    void anExceptionTheImplementationThrowsReachesTheCallerAsItIs() {
        assertThatThrownBy(() -> proxyOver(local()).explode("boom"))
                .isExactlyInstanceOf(EchoFailureException.class)
                .hasMessage("boom");
    }

    @Test
    void anInterceptorCanAnswerWithoutCallingTheTarget() {
        ServiceCallInterceptor cached = (invocation, chain) -> "cached";

        assertThat(proxyOver(local(cached)).echo("x")).isEqualTo("cached");
        assertThat(implementation.getCallCount()).isZero();
    }

    @Test
    void aCallArrivingOverTheTransportSkipsTheInterceptors() throws Exception {
        ServiceCallInterceptor interceptor = (invocation, chain) -> {
            events.add("intercepted");
            return chain.proceed();
        };
        ServiceBinding binding = local(interceptor);

        Object result = binding.invokeLocal(EchoService.class.getMethod("echo", String.class), new Object[] {"x"});

        assertThat(result).isEqualTo("echo:x");
        assertThat(events).isEmpty();
    }

    @Test
    void theInterceptorsAreAskedForOnTheFirstCallAndOnlyThen() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<List<ServiceCallInterceptor>> source = () -> {
            asked.incrementAndGet();
            return List.of();
        };
        ServiceBinding binding = ServiceBinding.local("echo-service", 1, implementation, source);
        EchoService proxy = proxyOver(binding);
        assertThat(asked).hasValue(0);

        proxy.echo("a");
        proxy.echo("b");

        assertThat(asked).hasValue(1);
    }

    @Test
    void aRemoteBindingIsNotHostedAndHasNothingToServe() throws Exception {
        ServiceBinding remote = ServiceBinding.remote("echo-service", 1, invocation -> "remote", List::of);

        assertThat(remote.isLocal()).isFalse();
        assertThat(local().isLocal()).isTrue();
        assertThatThrownBy(() -> remote.invokeLocal(EchoService.class.getMethod("echo", String.class), new Object[] {"x"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo-service");
    }

    @Test
    void aCheckedExceptionFromTheTransportIsWrappedAndARuntimeOneIsNot() {
        ServiceInvocation[] seen = new ServiceInvocation[1];
        EchoService checked = proxyOver(ServiceBinding.remote("echo-service", 1, invocation -> {
            seen[0] = invocation;
            throw new java.io.IOException("down");
        }, List::of));
        EchoService runtime = proxyOver(ServiceBinding.remote("echo-service", 1, invocation -> {
            throw new IllegalStateException("nope");
        }, List::of));

        assertThatThrownBy(() -> checked.echo("x"))
                .isInstanceOf(digital.demilich.henge.core.RemoteServiceException.class)
                .hasMessageContaining("echo-service#echo")
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> runtime.echo("x")).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(seen[0].serviceName()).isEqualTo("echo-service");
    }
}
