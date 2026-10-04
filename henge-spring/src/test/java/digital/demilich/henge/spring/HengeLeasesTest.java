package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.leasedfixture.ledger.LedgerService;
import digital.demilich.henge.spring.leasedfixture.ledger.ReportService;
import digital.demilich.henge.spring.leasedfixture.provided.FakePool;
import digital.demilich.henge.spring.leasedfixture.provided.PooledOneService;
import digital.demilich.henge.spring.leasedfixture.provided.PooledTwoService;
import digital.demilich.henge.spring.leasedfixture.two.TwoLeaseService;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class HengeLeasesTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
    static class LeasedConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.versioned")
    static class VersionedConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.provided")
    static class ProvidedConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.failing")
    static class FailingConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.noprovider")
    static class NoProviderConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.mismatch")
    static class MismatchConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.twoproviders")
    static class TwoProvidersConfig {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.two")
    static class TwoLeaseConfig {
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(configs);
        return ctx;
    }

    private static Map<String, Object> leases(int capacity, int amount) {
        return Map.of(
                "henge.leases.ledger-db.capacity", capacity,
                "henge.leases.ledger-db.amount", amount);
    }

    @Test
    void servicesThatFitTheLeaseAreConstructedWithTheirGrant() {
        try (var ctx = context(leases(100, 40), LeasedConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(LedgerService.class).grant()).isEqualTo("ledger-db:40");
            assertThat(ctx.getBean(ReportService.class).grant()).isEqualTo("ledger-db:40");

            var held = ctx.getBean(SystemEphemeralDatastore.class).read("lease:ledger-db").members();
            // One claim for the node, shared by both services.
            assertThat(held).hasSize(1);
            assertThat(held.values()).extracting(SystemEphemeralDatastore::claimedAmount).containsExactly(40);

            var registry = ctx.getBean(HengeServiceRegistry.class);
            assertThat(registry.find("ledger-service", 1)).isPresent();
            assertThat(registry.find("report-service", 1)).isPresent();
        }
    }

    @Test
    void theImplementationIsNotAnAutowireCandidateAlongsideItsInterface() {
        try (var ctx = context(leases(100, 40), LeasedConfig.class)) {
            ctx.refresh();

            // getIfUnique() resolves the way an injection point does, which skips non-candidates.
            assertThat(ctx.getBeanProvider(LedgerService.class).getIfUnique()).isNotNull();
        }
    }

    @Test
    void closingTheContextHandsTheLeasesBack() {
        SystemEphemeralDatastore store;
        try (var ctx = context(leases(100, 40), LeasedConfig.class)) {
            ctx.refresh();
            store = ctx.getBean(SystemEphemeralDatastore.class);
            assertThat(store.read("lease:ledger-db").members()).hasSize(1);
        }

        assertThat(store.read("lease:ledger-db").members()).isEmpty();
    }

    @Test
    void aMissingCapacityFailsStartupNamingTheProperty() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.remove("henge.leases.ledger-db.capacity");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("henge.leases.ledger-db.capacity isn't set");
        }
    }

    @Test
    void aMissingAmountFailsStartupNamingTheProperty() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.remove("henge.leases.ledger-db.amount");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("henge.leases.ledger-db.amount isn't set");
        }
    }

    @Test
    void anAmountLargerThanTheCapacityFailsStartup() {
        try (var ctx = context(leases(100, 101), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("could never be granted");
        }
    }

    @Test
    void aNonPositiveAmountFailsStartup() {
        try (var ctx = context(leases(100, 0), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("henge.leases.ledger-db.amount=0 is not a positive integer");
        }
    }

    @Test
    void thePerServiceAmountIsNoLongerAKeyAndSaysSo() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("henge.services.ledger-service.leases.ledger-db", 40);
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("henge.services.ledger-service.leases.ledger-db: not a known key");
        }
    }

    @Test
    void aLeaseNobodyDeclaresFailsStartup() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("henge.leases.ledger-dbb.capacity", 10);
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("henge.leases.ledger-dbb.capacity: no @RequiresLease names 'ledger-dbb'")
                    .hasStackTraceContaining("did you mean 'ledger-db'");
        }
    }

    @Test
    void leasesCantBeCombinedWithAUrlTemplate() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("henge.remote-url-template", "http://{service}:8080");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("can't be combined with henge.remote-url-template");
        }
    }

    @Test
    void aLeaseParameterMustSayWhichLeaseWhenSeveralAreDeclared() {
        try (var ctx = context(Map.of(
                "henge.leases.db-a.capacity", 10, "henge.leases.db-b.capacity", 10,
                "henge.leases.db-a.amount", 1, "henge.leases.db-b.amount", 1),
                TwoLeaseConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("must say which one with @RequiresLease");
        }
    }

    @Test
    void aServiceConfiguredInternalRestNeedsNoLease() {
        try (var ctx = context(Map.of(
                "henge.serve", "report-service",
                "henge.services.ledger-service.url", "http://elsewhere:8080",
                "henge.leases.ledger-db.capacity", 100,
                "henge.leases.ledger-db.amount", 50), LeasedConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(HengeServiceRegistry.class).find("ledger-service", 1)).isEmpty();
            assertThat(ctx.getBean(ReportService.class).grant()).isEqualTo("ledger-db:50");
        }
    }

    /** A datastore that behaves as if another node already held {@code foreign} of every lease. */
    static class ForeignHolderDatastore implements SystemEphemeralDatastore {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();
        private final int foreign;

        ForeignHolderDatastore(int foreign) {
            this.foreign = foreign;
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            Map<MemberId, byte[]> members = new HashMap<>(delegate.read(key).members());
            if (key.startsWith("lease:")) {
                members.put(new MemberId("another-node", "x"), ByteBuffer.allocate(Integer.BYTES).putInt(foreign).array());
            }
            return new Snapshot(members, delegate.read(key).epoch());
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return capacity - foreign >= 0 && delegate.claim(key, localName, amount, capacity - foreign, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    @Configuration
    static class ForeignHolderConfig {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new ForeignHolderDatastore(70);
        }
    }

    @Test
    void aLeaseHeldByOtherProcessesSendsItsServicesRemoteInstead() {
        // 70 held elsewhere leaves 30, so a claim of 40 is refused, and with it both services on the lease.
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("henge.services.ledger-service.url", "http://ledger-host:8080");
        properties.put("henge.services.report-service.url", "http://report-host:8080");
        try (var ctx = context(properties, LeasedConfig.class, ForeignHolderConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();

            var registry = ctx.getBean(HengeServiceRegistry.class);
            assertThat(registry.find("ledger-service", 1)).isEmpty();
            assertThat(registry.find("report-service", 1)).isEmpty();
            assertThat(java.lang.reflect.Proxy.isProxyClass(ctx.getBean(LedgerService.class).getClass())).isTrue();
            assertThat(java.lang.reflect.Proxy.isProxyClass(ctx.getBean(ReportService.class).getClass())).isTrue();

            // Only what is actually hosted here is advertised.
            var datastore = ctx.getBean(SystemEphemeralDatastore.class);
            assertThat(datastore.read("adv:ledger-service@1").members()).isEmpty();
            assertThat(datastore.read("adv:report-service@1").members()).isEmpty();
        }
    }

    @Test
    void servicesOnTheSameLeaseAreBothHostedWhenOneClaimFits() {
        // 70 held elsewhere leaves 30: one claim of 20 fits, and both services stand on it.
        try (var ctx = context(leases(100, 20), LeasedConfig.class, ForeignHolderConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();

            var registry = ctx.getBean(HengeServiceRegistry.class);
            assertThat(registry.find("ledger-service", 1)).isPresent();
            assertThat(registry.find("report-service", 1)).isPresent();
        }
    }

    @Test
    void aRefusedLeaseNeedsNoUrlSinceTheServiceIsFoundWhereItIsAdvertised() {
        // 70 held elsewhere leaves 30, so a claim of 40 is refused for both services.
        try (var ctx = context(leases(100, 40), LeasedConfig.class, ForeignHolderConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();

            var ledger = ctx.getBean(LedgerService.class);
            assertThat(java.lang.reflect.Proxy.isProxyClass(ledger.getClass())).isTrue();
            // Nobody advertises it in this test, and the call says so rather than naming a missing url.
            assertThatThrownBy(() -> ledger.grant()).hasMessageContaining("no process advertises it");
        }
    }

    @Test
    void everyVersionOfEveryServiceSharesTheNodesOneClaim() {
        try (var ctx = context(Map.of(
                "henge.leases.shared-db.capacity", 100,
                "henge.leases.shared-db.amount", 10),
                VersionedConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).hasSize(4);
            var held = ctx.getBean(SystemEphemeralDatastore.class).read("lease:shared-db").members();
            assertThat(held).hasSize(1);
            assertThat(held.values()).extracting(SystemEphemeralDatastore::claimedAmount).containsExactly(10);
        }
    }

    /** Refuses one lease and grants the rest, as if another node held all of it. */
    static class RefusesDbBDatastore implements SystemEphemeralDatastore {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return !key.equals("lease:db-b") && delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    @Configuration
    static class RefusesDbBConfig {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new RefusesDbBDatastore();
        }
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.multi")
    static class MultiLeaseConfig {
    }

    @Test
    void aLeaseWhoseOnlyConsumerWasRefusedAnotherLeaseIsNotKept() {
        try (var ctx = context(Map.of(
                "henge.leases.db-a.capacity", 10, "henge.leases.db-a.amount", 5,
                "henge.leases.db-b.capacity", 10, "henge.leases.db-b.amount", 5),
                MultiLeaseConfig.class, RefusesDbBConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();

            // db-a was granted, then db-b was refused: the service is remote, and db-a has no use here.
            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).isEmpty();
            assertThat(ctx.getBean(SystemEphemeralDatastore.class).read("lease:db-a").members()).isEmpty();
        }
    }

    private static Map<String, Object> lease(String name, int capacity, int amount) {
        return Map.of("henge.leases." + name + ".capacity", capacity, "henge.leases." + name + ".amount", amount);
    }

    @Test
    void servicesOnALeaseWithAProviderShareItsOneResource() {
        FakePool.OPENED.set(0);
        FakePool.CLOSED.set(0);
        SystemEphemeralDatastore store;
        try (var ctx = context(lease("pool-db", 100, 40), ProvidedConfig.class)) {
            ctx.refresh();
            store = ctx.getBean(SystemEphemeralDatastore.class);

            String one = ctx.getBean(PooledOneService.class).pool();
            String two = ctx.getBean(PooledTwoService.class).pool();
            assertThat(one).isEqualTo(two).endsWith(":40");
            assertThat(FakePool.OPENED).hasValue(1);
            assertThat(FakePool.CLOSED).hasValue(0);
            assertThat(store.read("lease:pool-db").members()).hasSize(1);
        }

        assertThat(FakePool.CLOSED).hasValue(1);
        assertThat(store.read("lease:pool-db").members()).isEmpty();
    }

    @Test
    void theResourceOfARefusedLeaseIsNeverOpened() {
        FakePool.OPENED.set(0);
        // 70 held elsewhere leaves 30, so a claim of 40 is refused.
        try (var ctx = context(lease("pool-db", 100, 40), ProvidedConfig.class, ForeignHolderConfig.class,
                HengeTransportConfiguration.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(HengeServiceRegistry.class).hosted()).isEmpty();
            assertThat(FakePool.OPENED).hasValue(0);
        }
    }

    @Test
    void aProviderThatFailsToOpenFailsStartupNamingTheLease() {
        try (var ctx = context(lease("fail-db", 10, 5), FailingConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("provider of lease 'fail-db' failed to open its resource")
                    .hasStackTraceContaining("database is down");
        }
    }

    @Test
    void aResourceParameterWithoutAProviderFailsStartupSayingHowToFixIt() {
        try (var ctx = context(lease("orphan-db", 10, 5), NoProviderConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("asks for the resource of lease 'orphan-db', but no @LeasedResource(\"orphan-db\") provider was found")
                    .hasStackTraceContaining("take a Lease parameter instead");
        }
    }

    @Test
    void aResourceThatDoesntFitTheParameterFailsStartup() {
        try (var ctx = context(lease("mm-db", 10, 5), MismatchConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("is a java.lang.Integer, but the provider of lease 'mm-db'")
                    .hasStackTraceContaining("makes a java.lang.String");
        }
    }

    @Test
    void twoProvidersForOneLeaseFailStartup() {
        try (var ctx = context(lease("dup-db", 10, 5), TwoProvidersConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("Two providers for lease 'dup-db'");
        }
    }
}
