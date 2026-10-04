package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.spring.fixture.echo.EchoFailureException;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ServiceBindingTest {

    private final EchoServiceImpl implementation = new EchoServiceImpl();
    private final List<String> events = new ArrayList<>();

    private static EchoService proxyOver(ServiceBinding binding) {
        return (EchoService) Proxy.newProxyInstance(EchoService.class.getClassLoader(), new Class<?>[] {EchoService.class},
                new HengeServiceInvocationHandler(binding));
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
        List<HengeMode> modes = new ArrayList<>();
        ServiceCallInterceptor record = (invocation, chain) -> {
            modes.add(chain.mode());
            return chain.proceed();
        };

        proxyOver(local(record)).echo("x");
        proxyOver(ServiceBinding.remote("echo-service", 1, invocation -> "remote", () -> List.of(record))).echo("x");

        assertThat(modes).containsExactly(HengeMode.EMBEDDED, HengeMode.INTERNAL_REST);
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

    @Test
    void aRetiredBindingSendsCallsToTheTransportAndNoLongerHostsTheImplementation() throws Exception {
        ServiceBinding binding = local();
        EchoService proxy = proxyOver(binding);

        assertThat(binding.retire(invocation -> "remote", Duration.ofSeconds(1))).isTrue();

        assertThat(binding.isLocal()).isFalse();
        assertThat(proxy.echo("x")).isEqualTo("remote");
        assertThat(implementation.getCallCount()).isZero();
        assertThatThrownBy(() -> binding.invokeLocal(EchoService.class.getMethod("echo", String.class), new Object[] {"x"}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retiringWaitsForTheCallsAlreadyRunningInTheImplementation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EchoServiceImpl slow = new EchoServiceImpl() {
            @Override
            public String echo(String value) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return "slow:" + value;
            }
        };
        ServiceBinding binding = ServiceBinding.local("echo-service", 1, slow, List::of);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> running = pool.submit(() -> proxyOver(binding).echo("x"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> retiring = pool.submit(() -> binding.retire(invocation -> "remote", Duration.ofSeconds(10)));

            // New calls already go elsewhere while the old one is still running, and retirement waits for it.
            while (binding.isLocal()) {
                Thread.onSpinWait();
            }
            assertThat(proxyOver(binding).echo("y")).isEqualTo("remote");
            assertThat(retiring.isDone()).isFalse();

            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("slow:x");
            assertThat(retiring.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void retiringGivesUpWaitingAfterTheTimeoutAndSaysSo() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EchoServiceImpl stuck = new EchoServiceImpl() {
            @Override
            public String echo(String value) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return value;
            }
        };
        ServiceBinding binding = ServiceBinding.local("echo-service", 1, stuck, List::of);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> proxyOver(binding).echo("x"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(binding.retire(invocation -> "remote", Duration.ofMillis(50))).isFalse();
            assertThat(binding.isLocal()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void retiringARemoteBindingDoesNothing() throws Exception {
        ServiceBinding remote = ServiceBinding.remote("echo-service", 1, invocation -> "first", List::of);

        assertThat(remote.retire(invocation -> "second", Duration.ofSeconds(1))).isTrue();

        assertThat(proxyOver(remote).echo("x")).isEqualTo("first");
    }
}
