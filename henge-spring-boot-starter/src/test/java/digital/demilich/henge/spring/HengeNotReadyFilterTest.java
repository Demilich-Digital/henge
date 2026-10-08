package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A process that hasn't reached the ephemeral store refuses everything but its health checks. */
class HengeNotReadyFilterTest {

    private final AtomicBoolean storeDown = new AtomicBoolean(true);

    /** The in-process store, until {@code storeDown}. */
    private final SystemEphemeralDatastore store = new SystemEphemeralDatastore() {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();

        private void reachable() {
            if (storeDown.get()) {
                throw new StoreUnavailableException("down");
            }
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            reachable();
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            reachable();
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            reachable();
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            reachable();
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
            reachable();
            return delegate.tryAcquire(key, amount, limit);
        }
    };

    private final HengeBootGate gate = new HengeBootGate(store);

    private final AtomicInteger served = new AtomicInteger();
    private final FilterChain chain = (request, response) -> served.incrementAndGet();

    private HengeNotReadyFilter filter() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("hengeBootGate", gate);
        return new HengeNotReadyFilter(beans.getBeanProvider(HengeBootGate.class), "/actuator");
    }

    private MockHttpServletResponse get(HengeNotReadyFilter filter, String uri) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void everythingIsRefusedWith503UntilTheStoreIsReached() throws Exception {
        gate.start();
        HengeNotReadyFilter filter = filter();

        MockHttpServletResponse response = get(filter, "/api/orders");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(get(filter, "/_henge/inventory/1/stock").getStatus()).isEqualTo(503);
        assertThat(served).hasValue(0);
        gate.stop();
    }

    @Test
    void theHealthEndpointsAreLetThroughSoTheProcessCanSayItIsAliveAndNotReady() throws Exception {
        gate.start();
        HengeNotReadyFilter filter = filter();

        get(filter, "/actuator/health");
        get(filter, "/actuator/health/liveness");
        get(filter, "/actuator/health/readiness");

        assertThat(served).hasValue(3);
        gate.stop();
    }

    @Test
    void aLookalikePathIsNotAHealthEndpoint() throws Exception {
        gate.start();

        assertThat(get(filter(), "/actuator/healthy-food").getStatus()).isEqualTo(503);
        gate.stop();
    }

    @Test
    void onceReadyEverythingIsServedAndAStoreOutageAfterwardsChangesNothing() throws Exception {
        storeDown.set(false);
        gate.start();
        HengeNotReadyFilter filter = filter();

        assertThat(get(filter, "/api/orders").getStatus()).isEqualTo(200);

        storeDown.set(true);
        assertThat(get(filter, "/api/orders").getStatus()).isEqualTo(200);
        assertThat(served).hasValue(2);
    }

    @Test
    void aProcessWithNoGateIsAlwaysReady() throws Exception {
        var noGate = new HengeNotReadyFilter(new StaticListableBeanFactory().getBeanProvider(HengeBootGate.class), "/actuator");

        assertThat(get(noGate, "/api/orders").getStatus()).isEqualTo(200);
    }

    @Test
    void theRunnerWaitsForTheGateToBeReady() throws Exception {
        storeDown.set(false);
        gate.start();
        var beans = new StaticListableBeanFactory();
        beans.addBean("hengeBootGate", gate);

        new HengeBootGateRunner(beans.getBeanProvider(HengeBootGate.class)).run(null);

        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void theGateBecomesReadyOnItsOwnWhenTheStoreReturns() throws Exception {
        gate.start();
        assertThat(gate.isReady()).isFalse();

        storeDown.set(false);
        gate.awaitReady();

        assertThat(gate.isReady()).isTrue();
    }
}
