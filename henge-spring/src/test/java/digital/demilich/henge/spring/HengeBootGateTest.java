package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.leasedfixture.ledger.LedgerService;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

/**
 * A process started while the ephemeral store is away starts anyway: not ready, refusing what needs the
 * store, and ready once it has reached it.
 */
class HengeBootGateTest {

    /** The in-process store, and an outage we can start and end. */
    static class SwitchableStore implements SystemEphemeralDatastore {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();
        volatile boolean down;

        private void reachable() {
            if (down) {
                throw new StoreUnavailableException("Can't connect to the store");
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
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
    @Import(HengeTransportConfiguration.class)
    static class LedgerConfig {
        static volatile SwitchableStore STORE = new SwitchableStore();

        @Bean
        SystemEphemeralDatastore store() {
            return STORE;
        }
    }

    private static AnnotationConfigApplicationContext start(boolean storeDown) {
        LedgerConfig.STORE = new SwitchableStore();
        LedgerConfig.STORE.down = storeDown;
        Map<String, Object> properties = new HashMap<>();
        properties.put("henge.leases.ledger-db.capacity", 100);
        properties.put("henge.leases.ledger-db.amount", 40);
        properties.put("henge.advertise.url", "http://pod-1:8080");
        // Probe a store that is away often, so the test doesn't wait out the default backoff.
        properties.put("henge.store.backoff.initial", "10ms");
        properties.put("henge.store.backoff.max", "50ms");
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(LedgerConfig.class);
        ctx.refresh();
        return ctx;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void aProcessWhoseStoreIsThereIsReadyAtOnce() {
        try (var ctx = start(false)) {
            assertThat(ctx.getBean(HengeBootGate.class).isReady()).isTrue();
            assertThat(ctx.getBean(LedgerService.class).grant()).isEqualTo("ledger-db:40");
        }
    }

    @Test
    void aProcessStartedWithoutTheStoreStartsNotReadyAndRefusesWhatNeedsIt() {
        try (var ctx = start(true)) {
            assertThat(ctx.getBean(HengeBootGate.class).isReady()).isFalse();
            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).isEmpty();
            assertThatThrownBy(() -> ctx.getBean(LedgerService.class).grant())
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessageContaining("ledger-service");
        }
    }

    @Test
    void onceTheStoreIsReachedItDecidesItsLeasedServicesAndAdvertisesThem() throws Exception {
        try (var ctx = start(true)) {
            HengeBootGate gate = ctx.getBean(HengeBootGate.class);
            assertThat(gate.isReady()).isFalse();

            LedgerConfig.STORE.down = false;
            await(gate::isReady);

            assertThat(ctx.getBean(LedgerService.class).grant()).isEqualTo("ledger-db:40");
            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).extracting(HengeServiceDescriptor::name)
                    .contains("ledger-service", "report-service");
            await(() -> !LedgerConfig.STORE.read("adv:ledger-service@1").members().isEmpty());
            assertThat(LedgerConfig.STORE.read("lease:ledger-db").members()).hasSize(1);
        }
    }

    @Test
    void aLeaseTheClusterHasNoRoomForIsDecidedRemoteOnceTheStoreIsReached() throws Exception {
        try (var ctx = start(true)) {
            HengeBootGate gate = ctx.getBean(HengeBootGate.class);
            // Another node holds all of it.
            LedgerConfig.STORE.delegate.put("lease:ledger-db", "other-node", ByteBuffer.allocate(Integer.BYTES).putInt(100).array(),
                    Duration.ofMinutes(5));

            LedgerConfig.STORE.down = false;
            await(gate::isReady);

            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).isEmpty();
        }
    }
}
