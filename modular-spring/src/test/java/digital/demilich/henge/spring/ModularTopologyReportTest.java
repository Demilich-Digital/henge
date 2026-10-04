package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import digital.demilich.henge.spring.ModularTopologyReport.Report;
import digital.demilich.henge.spring.ModularTopologyReport.RouteSource;
import digital.demilich.henge.spring.ModularTopologyReport.Service;
import digital.demilich.henge.spring.ModularTopologyReport.State;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class ModularTopologyReportTest {

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
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
        properties.put("modular.leases.ledger-db.capacity", 100);
        properties.put("modular.services.ledger-service.leases.ledger-db", 40);
        properties.put("modular.services.report-service.leases.ledger-db", 20);
        return properties;
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new HashMap<>(properties)));
        ctx.register(configs);
        return ctx;
    }

    private static Report reportOf(AnnotationConfigApplicationContext ctx) {
        var properties = new ModularProperties(ctx.getEnvironment());
        return new ModularTopologyReport(ctx, ctx.getBean(ModularTopologyCatalog.class), ctx.getBean(ModularServiceRegistry.class),
                ctx.getBean(SystemEphemeralDatastore.class), properties, ctx.getEnvironment()).report();
    }

    private static Service service(Report report, String id) {
        return report.services().stream().filter(service -> service.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void whatThisProcessHostsIsReportedWithItsLeasesAndAdvertisement() {
        Map<String, Object> properties = leases();
        properties.put("modular.advertise.url", "http://me:8080");
        try (var ctx = context(properties, LeasedConfig.class)) {
            ctx.refresh();

            Report report = reportOf(ctx);

            Service ledger = service(report, "ledger-service@1");
            assertThat(ledger.state()).isEqualTo(State.HOSTED);
            assertThat(ledger.route()).isNull();
            assertThat(ledger.defaultVersion()).isTrue();
            assertThat(ledger.modeSource()).isEqualTo(ModularTopologyCatalog.ModeSource.DEFAULT);
            assertThat(ledger.implementation()).endsWith("LedgerServiceImpl");
            assertThat(ledger.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.name()).isEqualTo("ledger-db");
                assertThat(lease.amount()).isEqualTo(40);
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
                assertThat(lease.claimed()).isEqualTo(60);
                assertThat(lease.holders()).extracting(holder -> holder.service() + " " + holder.amount())
                        .containsExactlyInAnyOrder("ledger-service@1 40", "report-service@1 20");
            });
        }
    }

    @Test
    void aServiceWhoseLeaseWasRefusedIsReportedRemoteAndRoutedLikeACall() {
        Map<String, Object> properties = leases();
        try (var ctx = context(properties, LeasedConfig.class, CrowdedConfig.class, ModularTransportConfiguration.class)) {
            ctx.refresh();

            // 70 is held elsewhere, so only 30 is left: report (20) fits, ledger (40) doesn't.
            Report report = reportOf(ctx);
            assertThat(service(report, "report-service@1").state()).isEqualTo(State.HOSTED);
            Service ledger = service(report, "ledger-service@1");
            assertThat(ledger.state()).isEqualTo(State.LEASE_REFUSED);
            assertThat(ledger.route().source()).isEqualTo(RouteSource.NONE);
            assertThat(report.leases()).singleElement().satisfies(lease -> {
                assertThat(lease.claimed()).isEqualTo(70 + 20);
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
        properties.put("modular.services.ledger-service.url", "http://configured:1");
        try (var ctx = context(properties, LeasedConfig.class, CrowdedConfig.class, ModularTransportConfiguration.class)) {
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
                "modular.services.echo-service.mode", "internal-rest",
                "modular.remote-url-template", "http://{service}-v{version}.svc:8080");
        try (var ctx = context(properties, EchoTestConfig.class)) {
            ctx.refresh();

            Service echo = service(reportOf(ctx), "echo-service@1");
            assertThat(echo.state()).isEqualTo(State.REMOTE);
            assertThat(echo.modeSource()).isEqualTo(ModularTopologyCatalog.ModeSource.EXPLICIT);
            assertThat(echo.route().source()).isEqualTo(RouteSource.TEMPLATE);
            assertThat(echo.route().urls()).containsExactly("http://echo-service-v1.svc:8080");
            assertThat(echo.implementation()).endsWith("EchoServiceImpl");
        }
    }

    @Test
    void aDatastoreThatCantBeReadLeavesTheRestOfTheReportIntact() {
        try (var ctx = context(leases(), LeasedConfig.class, DownConfig.class)) {
            ctx.refresh();
            ((DownDatastore) ctx.getBean(SystemEphemeralDatastore.class)).down = true;

            Report report = reportOf(ctx);

            assertThat(report.store().readable()).isFalse();
            assertThat(report.store().error()).contains("connection refused");
            assertThat(report.services()).hasSize(2);
            assertThat(service(report, "ledger-service@1").advertisedBy()).isEmpty();
            assertThat(service(report, "ledger-service@1").state()).isEqualTo(State.HOSTED);
        }
    }
}
