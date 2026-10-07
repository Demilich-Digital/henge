package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import digital.demilich.henge.spring.HengeTopologyReport.Report;
import digital.demilich.henge.spring.HengeTopologyReport.RouteSource;
import digital.demilich.henge.spring.HengeTopologyReport.Service;
import digital.demilich.henge.spring.HengeTopologyReport.State;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class HengeTopologyReportTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
    static class LeasedConfig {
    }

    /** A datastore where 70 of every lease is already held by another node. */
    static class CrowdedDatastore implements SystemEphemeralDatastore {
        private static final int FOREIGN = 70;
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
            Map<MemberId, byte[]> members = new HashMap<>(delegate.read(key).members());
            if (key.startsWith("lease:")) {
                members.put(new MemberId("another-node", "x"), ByteBuffer.allocate(Integer.BYTES).putInt(FOREIGN).array());
            }
            return new Snapshot(members, delegate.read(key).epoch());
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return delegate.claim(key, localName, amount, capacity - FOREIGN, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    /** A datastore that can't be read once told it is down. */
    static class DownDatastore implements SystemEphemeralDatastore {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();
        boolean down;

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
            if (down) {
                throw new IllegalStateException("connection refused");
            }
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    @Configuration
    static class CrowdedConfig {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new CrowdedDatastore();
        }
    }

    @Configuration
    static class DownConfig {
        @Bean
        SystemEphemeralDatastore datastore() {
            return new DownDatastore();
        }
    }

    private static Map<String, Object> leases() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("henge.leases.ledger-db.capacity", 100);
        properties.put("henge.leases.ledger-db.amount", 30);
        return properties;
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(configs);
        return ctx;
    }

    private static Report reportOf(AnnotationConfigApplicationContext ctx) {
        var properties = new HengeProperties(ctx.getEnvironment());
        return new HengeTopologyReport(ctx, ctx.getBean(HengeTopologyCatalog.class), ctx.getBean(HengeServiceRegistry.class),
                ctx.getBean(SystemEphemeralDatastore.class), properties, ctx.getEnvironment()).report();
    }

    private static Service service(Report report, String id) {
        return report.services().stream().filter(service -> service.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void whatThisProcessHostsIsReportedWithItsLeasesAndAdvertisement() {
        Map<String, Object> properties = leases();
        properties.put("henge.advertise.url", "http://me:8080");
        try (var ctx = context(properties, LeasedConfig.class)) {
            ctx.refresh();

            Report report = reportOf(ctx);

            Service ledger = service(report, "ledger-service@1");
            assertThat(ledger.state()).isEqualTo(State.HOSTED);
            assertThat(ledger.route()).isNull();
            assertThat(ledger.defaultVersion()).isTrue();
            assertThat(ledger.modeSource()).isEqualTo(HengeTopologyCatalog.ModeSource.DEFAULT);
            assertThat(ledger.implementation()).endsWith("LedgerServiceImpl");
            assertThat(ledger.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.name()).isEqualTo("ledger-db");
                assertThat(lease.amount()).isEqualTo(30);
                assertThat(lease.capacity()).isEqualTo(100);
            });
            assertThat(ledger.advertisedBy()).singleElement().satisfies(advertiser -> {
                assertThat(advertiser.url()).isEqualTo("http://me:8080");
                assertThat(advertiser.self()).isTrue();
            });

            assertThat(report.node().advertiseUrl()).isEqualTo("http://me:8080");
            assertThat(report.node().storeType()).isEqualTo("in-process");
            assertThat(report.store().readable()).isTrue();
            assertThat(report.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.capacity()).isEqualTo(100);
                // One claim for the node, shared by both services.
                assertThat(lease.claimed()).isEqualTo(30);
                assertThat(lease.holders()).singleElement().satisfies(holder -> {
                    assertThat(holder.amount()).isEqualTo(30);
                    assertThat(holder.self()).isTrue();
                });
            });
        }
    }

    @Test
    void aClaimBeingGivenUpIsReportedAsHeldAndMarkedLeaving() {
        try (var ctx = context(leases(), LeasedConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();
            // Another node is giving up 20: it is still in use until it has closed the resource.
            ctx.getBean(SystemEphemeralDatastore.class).claim("lease:ledger-db", "other", -20, 100, java.time.Duration.ofSeconds(30));

            Report report = reportOf(ctx);

            assertThat(report.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.claimed()).isEqualTo(50);
                assertThat(lease.holders()).filteredOn(HengeTopologyReport.Holder::leaving).singleElement()
                        .satisfies(holder -> assertThat(holder.amount()).isEqualTo(20));
            });
        }
    }

    @Test
    void aServiceWhoseLeaseWasRefusedIsReportedRemoteAndRoutedLikeACall() {
        Map<String, Object> properties = leases();
        properties.put("henge.leases.ledger-db.amount", 40);
        try (var ctx = context(properties, LeasedConfig.class, CrowdedConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();

            // 70 is held elsewhere, so only 30 is left: a claim of 40 fits for neither service.
            Report report = reportOf(ctx);
            assertThat(service(report, "report-service@1").state()).isEqualTo(State.LEASE_REFUSED);
            Service ledger = service(report, "ledger-service@1");
            assertThat(ledger.state()).isEqualTo(State.LEASE_REFUSED);
            assertThat(ledger.route().source()).isEqualTo(RouteSource.NONE);
            assertThat(report.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.claimed()).isEqualTo(70);
                assertThat(lease.holders()).anyMatch(holder -> holder.node().equals("another-node") && !holder.self());
            });

            // Someone advertises it: that's where its calls would go.
            ctx.getBean(SystemEphemeralDatastore.class).put("adv:ledger-service@1", "host",
                    new ServiceAdvertisement("http://elsewhere:9090").encode(), Duration.ofMinutes(1));
            Service found = service(reportOf(ctx), "ledger-service@1");
            assertThat(found.route().source()).isEqualTo(RouteSource.ADVERTISED);
            assertThat(found.route().urls()).containsExactly("http://elsewhere:9090");
        }
    }

    @Test
    void aConfiguredUrlBeatsTheAdvertisements() {
        Map<String, Object> properties = leases();
        properties.put("henge.leases.ledger-db.amount", 40);
        properties.put("henge.services.ledger-service.url", "http://configured:1");
        try (var ctx = context(properties, LeasedConfig.class, CrowdedConfig.class, HengeTransportConfiguration.class)) {
            ctx.refresh();
            ctx.getBean(SystemEphemeralDatastore.class).put("adv:ledger-service@1", "host",
                    new ServiceAdvertisement("http://elsewhere:9090").encode(), Duration.ofMinutes(1));

            Service ledger = service(reportOf(ctx), "ledger-service@1");
            assertThat(ledger.route().source()).isEqualTo(RouteSource.CONFIGURED_URL);
            assertThat(ledger.route().urls()).containsExactly("http://configured:1");
        }
    }

    @Test
    void aServiceConfiguredRemoteIsRoutedByTheTemplateAndSaysWhereItsModeCameFrom() {
        // Not a leased service: a template can't be combined with those.
        Map<String, Object> properties = Map.of(
                "henge.store.type", "in-process",
                "henge.services.echo-service.mode", "internal-rest",
                "henge.remote-url-template", "http://{service}-v{version}.svc:8080");
        try (var ctx = context(properties, EchoTestConfig.class)) {
            ctx.refresh();

            Service echo = service(reportOf(ctx), "echo-service@1");
            assertThat(echo.state()).isEqualTo(State.REMOTE);
            assertThat(echo.modeSource()).isEqualTo(HengeTopologyCatalog.ModeSource.EXPLICIT);
            assertThat(echo.route().source()).isEqualTo(RouteSource.TEMPLATE);
            assertThat(echo.route().urls()).containsExactly("http://echo-service-v1.svc:8080");
            assertThat(echo.implementation()).endsWith("EchoServiceImpl");
        }
    }

    @Test
    void aDatastoreThatCantBeReadLeavesTheRestOfTheReportIntact() {
        try (var ctx = context(leases(), LeasedConfig.class, DownConfig.class)) {
            ctx.refresh();
            ((DownDatastore) ((GuardedDatastore) ctx.getBean(SystemEphemeralDatastore.class)).delegate()).down = true;

            Report report = reportOf(ctx);

            assertThat(report.store().readable()).isFalse();
            assertThat(report.store().error()).contains("connection refused");
            assertThat(report.services()).hasSize(2);
            assertThat(service(report, "ledger-service@1").advertisedBy()).isEmpty();
            assertThat(service(report, "ledger-service@1").state()).isEqualTo(State.HOSTED);
        }
    }
}
