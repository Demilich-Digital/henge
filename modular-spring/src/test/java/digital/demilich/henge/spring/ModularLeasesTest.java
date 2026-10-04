package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.leasedfixture.ledger.LedgerService;
import digital.demilich.henge.spring.leasedfixture.ledger.ReportService;
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

class ModularLeasesTest {

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
    static class LeasedConfig {
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.leasedfixture.versioned")
    static class VersionedConfig {
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.leasedfixture.two")
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
                "modular.leases.ledger-db.capacity", capacity,
                "modular.leases.ledger-db.amount", amount);
    }

    @Test
    void servicesThatFitTheLeaseAreConstructedWithTheirGrant() {
        try (var ctx = context(leases(100, 40), LeasedConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(LedgerService.class).grant()).isEqualTo("ledger-db:40");
            assertThat(ctx.getBean(ReportService.class).grant()).isEqualTo("ledger-db:40");

            var held = ctx.getBean(SystemEphemeralDatastore.class).read("lease:ledger-db").members();
            assertThat(held).hasSize(2);
            assertThat(held.values()).extracting(SystemEphemeralDatastore::claimedAmount).containsExactlyInAnyOrder(40, 40);

            var registry = ctx.getBean(ModularServiceRegistry.class);
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
            assertThat(store.read("lease:ledger-db").members()).hasSize(2);
        }

        assertThat(store.read("lease:ledger-db").members()).isEmpty();
    }

    @Test
    void aMonolithThatOverAllocatesALeaseFailsAtBootNamingEveryService() {
        try (var ctx = context(leases(100, 60), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("Lease 'ledger-db' has capacity 100, but one instance of each service that declares it claims 120")
                    .hasStackTraceContaining("ledger-service@1 60")
                    .hasStackTraceContaining("report-service@1 60")
                    .hasStackTraceContaining("no deployment of this jar, however it's split, can host them all")
                    .hasStackTraceContaining("Raise modular.leases.ledger-db.capacity");
        }
    }

    @Test
    void aUrlToFallBackOnDoesNotExcuseOverAllocation() {
        Map<String, Object> properties = new HashMap<>(leases(100, 60));
        properties.put("modular.services.report-service.url", "http://report-host:8080");
        try (var ctx = context(properties, LeasedConfig.class, ModularTransportConfiguration.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("claims 120");
        }
    }

    @Test
    void aServiceThisProcessDoesntHostStillCountsOnceItsAmountIsConfigured() {
        Map<String, Object> properties = new HashMap<>(leases(100, 60));
        properties.put("modular.serve", "report-service");
        try (var ctx = context(properties, LeasedConfig.class, ModularTransportConfiguration.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("ledger-service@1 60");
        }
    }

    @Test
    void aMissingCapacityFailsStartupNamingTheProperty() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.remove("modular.leases.ledger-db.capacity");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("modular.leases.ledger-db.capacity isn't set");
        }
    }

    @Test
    void aMissingAmountFailsStartupNamingTheProperty() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.remove("modular.leases.ledger-db.amount");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("modular.leases.ledger-db.amount isn't set");
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
                    .hasStackTraceContaining("modular.leases.ledger-db.amount=0 is not a positive integer");
        }
    }

    @Test
    void thePerServiceAmountIsNoLongerAKeyAndSaysSo() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("modular.services.ledger-service.leases.ledger-db", 40);
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("modular.services.ledger-service.leases.ledger-db: not a known key");
        }
    }

    @Test
    void aLeaseNobodyDeclaresFailsStartup() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("modular.leases.ledger-dbb.capacity", 10);
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("modular.leases.ledger-dbb.capacity: no @RequiresLease names 'ledger-dbb'")
                    .hasStackTraceContaining("did you mean 'ledger-db'");
        }
    }

    @Test
    void leasesCantBeCombinedWithAUrlTemplate() {
        Map<String, Object> properties = new HashMap<>(leases(100, 40));
        properties.put("modular.remote-url-template", "http://{service}:8080");
        try (var ctx = context(properties, LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("can't be combined with modular.remote-url-template");
        }
    }

    @Test
    void aLeaseParameterMustSayWhichLeaseWhenSeveralAreDeclared() {
        try (var ctx = context(Map.of(
                "modular.leases.db-a.capacity", 10, "modular.leases.db-b.capacity", 10,
                "modular.leases.db-a.amount", 1, "modular.leases.db-b.amount", 1),
                TwoLeaseConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("must say which one with @RequiresLease");
        }
    }

    @Test
    void aServiceConfiguredInternalRestNeedsNoLease() {
        try (var ctx = context(Map.of(
                "modular.serve", "report-service",
                "modular.services.ledger-service.url", "http://elsewhere:8080",
                "modular.leases.ledger-db.capacity", 100,
                "modular.leases.ledger-db.amount", 50), LeasedConfig.class)) {
            ctx.refresh();

            assertThat(ctx.getBean(ModularServiceRegistry.class).find("ledger-service", 1)).isEmpty();
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
    }

    @Configuration
    static class ForeignHolderConfig {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new ForeignHolderDatastore(70);
        }
    }

    @Test
    void aLeaseHeldByOtherProcessesSendsTheServiceRemoteInstead() {
        Map<String, Object> properties = new HashMap<>(leases(100, 20));
        properties.put("modular.services.ledger-service.url", "http://ledger-host:8080");
        properties.put("modular.services.report-service.url", "http://report-host:8080");
        try (var ctx = context(properties, LeasedConfig.class, ForeignHolderConfig.class, ModularTransportConfiguration.class)) {
            ctx.refresh();

            // 70 held elsewhere leaves 30: one claim of 20 fits, so one of the two is reached remotely.
            // Which one is decided by claim order, which isn't the test's business.
            var registry = ctx.getBean(ModularServiceRegistry.class);
            boolean ledgerHere = registry.find("ledger-service", 1).isPresent();
            boolean reportHere = registry.find("report-service", 1).isPresent();
            assertThat(ledgerHere ^ reportHere).isTrue();
            Object refused = ledgerHere ? ctx.getBean(ReportService.class) : ctx.getBean(LedgerService.class);
            assertThat(java.lang.reflect.Proxy.isProxyClass(refused.getClass())).isTrue();

            // Only what is actually hosted here is advertised.
            var datastore = ctx.getBean(SystemEphemeralDatastore.class);
            assertThat(datastore.read("adv:ledger-service@1").members()).hasSize(ledgerHere ? 1 : 0);
            assertThat(datastore.read("adv:report-service@1").members()).hasSize(reportHere ? 1 : 0);
        }
    }

    @Test
    void aRefusedLeaseNeedsNoUrlSinceTheServiceIsFoundWhereItIsAdvertised() {
        // 70 held elsewhere leaves 30, so a claim of 40 is refused for both services.
        try (var ctx = context(leases(100, 40), LeasedConfig.class, ForeignHolderConfig.class, ModularTransportConfiguration.class)) {
            ctx.refresh();

            var ledger = ctx.getBean(LedgerService.class);
            assertThat(java.lang.reflect.Proxy.isProxyClass(ledger.getClass())).isTrue();
            // Nobody advertises it in this test, and the call says so rather than naming a missing url.
            assertThatThrownBy(() -> ledger.grant()).hasMessageContaining("no process advertises it");
        }
    }

    @Configuration
    static class ForeignHolderOf80Config {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new ForeignHolderDatastore(80);
        }
    }

    @Test
    void whenTheLeaseCantCoverEveryVersionTheNewestVersionOfEachServiceClaimsFirst() {
        // 20 of 100 is left: room for two claims of 10, and four versions want one. Every version of
        // both services is scanned oldest first, so only claiming newest first leaves v2 of each.
        try (var ctx = context(Map.of(
                "modular.leases.shared-db.capacity", 100,
                "modular.leases.shared-db.amount", 10),
                VersionedConfig.class, ForeignHolderOf80Config.class, ModularTransportConfiguration.class)) {
            ctx.refresh();

            var registry = ctx.getBean(ModularServiceRegistry.class);
            assertThat(registry.find("alpha-service", 2)).isPresent();
            assertThat(registry.find("beta-service", 2)).isPresent();
            assertThat(registry.find("alpha-service", 1)).isEmpty();
            assertThat(registry.find("beta-service", 1)).isEmpty();
        }
    }

    @Test
    void everyVersionIsHostedWhenTheLeaseCoversThemAll() {
        try (var ctx = context(Map.of(
                "modular.leases.shared-db.capacity", 100,
                "modular.leases.shared-db.amount", 10),
                VersionedConfig.class)) {
            ctx.refresh();

            var registry = ctx.getBean(ModularServiceRegistry.class);
            assertThat(registry.hosted()).hasSize(4);
        }
    }
}
