package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.RemoteServiceException;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.fixture.counter.CounterService;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV1;
import digital.demilich.henge.spring.fixture.echo.EchoService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/** What each component reports to {@link MicrometerSystemMetrics}, including when the datastore or the network fails. */
class MicrometerSystemMetricsTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final SystemMetrics metrics = new MicrometerSystemMetrics(meters);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final FlakyStore store = new FlakyStore(new InProcessEphemeralDatastore(now::get));

    /** A datastore that can be made to fail. */
    private static final class FlakyStore implements SystemEphemeralDatastore {
        private final SystemEphemeralDatastore delegate;
        volatile boolean failing;

        FlakyStore(SystemEphemeralDatastore delegate) {
            this.delegate = delegate;
        }

        private void check() {
            if (failing) {
                throw new IllegalStateException("store is down");
            }
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            check();
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            check();
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            check();
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            check();
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            check();
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timings(String purpose, String operation, String outcome) {
        var timer = meters.find("henge.store.operations").tags("purpose", purpose, "operation", operation, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    // -- leases

    @Test
    void aGrantedAndARefusedClaimAreCountedAndOnlyTheGrantedOneIsHeld() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);
        store.claim("lease:full", "other-node", 10, 10, Duration.ofSeconds(30));

        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        assertThat(keeper.acquireAll("b@1", List.of(new LeaseNeed("full", 5, 10)))).isNotNull();

        assertThat(count("henge.lease.claims", "lease", "db", "outcome", "granted")).isEqualTo(1);
        assertThat(count("henge.lease.claims", "lease", "full", "outcome", "refused")).isEqualTo(1);
        assertThat(meters.get("henge.lease.held").tag("lease", "db").gauge().value()).isEqualTo(5);
        assertThat(meters.find("henge.lease.held").tag("lease", "full").gauge()).isNull();
    }

    @Test
    void aServiceSharingAHeldLeaseDoesNotClaimItAgain() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);

        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)));
        keeper.acquireAll("b@1", List.of(new LeaseNeed("db", 5, 10)));

        assertThat(count("henge.lease.claims", "lease", "db", "outcome", "granted")).isEqualTo(1);
    }

    @Test
    void aLeaseHandedBackIsHeldAtZero() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);
        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)));

        keeper.release("a@1");

        assertThat(meters.get("henge.lease.held").tag("lease", "db").gauge().value()).isZero();
    }

    @Test
    void renewalsAreCountedByHowTheyWent() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);
        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)));

        keeper.renewAll();
        assertThat(count("henge.lease.renewals", "lease", "db", "outcome", "renewed")).isEqualTo(1);

        // Another node's member now takes more than is left beside ours.
        store.put("lease:db", "other-node", ByteBuffer.allocate(Integer.BYTES).putInt(8).array(), Duration.ofSeconds(30));
        keeper.renewAll();
        assertThat(count("henge.lease.renewals", "lease", "db", "outcome", "over-capacity")).isEqualTo(1);

        store.failing = true;
        keeper.renewAll();
        assertThat(count("henge.lease.renewals", "lease", "db", "outcome", "error")).isEqualTo(1);
    }

    // -- the datastore

    @Test
    void everyDatastoreOperationIsTimedByWhoAskedAndHowItWent() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);

        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)));
        keeper.release("a@1");
        store.failing = true;
        keeper.renewAll(); // nothing is held any more, so nothing is asked of the store

        assertThat(timings("lease", "claim", "success")).isEqualTo(1);
        assertThat(timings("lease", "remove", "success")).isEqualTo(1);
        assertThat(timings("lease", "claim", "error")).isZero();
    }

    @Test
    void aRefusedClaimIsASuccessfulOperationAndAFailedOneIsAnError() {
        var keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), metrics);
        store.claim("lease:full", "other-node", 10, 10, Duration.ofSeconds(30));

        keeper.acquireAll("a@1", List.of(new LeaseNeed("full", 5, 10))); // refused
        store.failing = true;
        assertThatThrownBy(() -> keeper.acquireAll("b@1", List.of(new LeaseNeed("other", 5, 10))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(timings("lease", "claim", "success")).isEqualTo(1);
        assertThat(timings("lease", "claim", "error")).isEqualTo(1);
    }

    // -- advertisements

    @Test
    void advertisementRenewalsAreCountedByServiceVersionAndOutcome() {
        try (var hosting = new GenericApplicationContext()) {
            hosting.registerBean("counter-service-1", HengeServiceBindingFactoryBean.class, () -> new HengeServiceBindingFactoryBean(
                    ServiceBindingSpec.embedded(CounterService.class, "counter-service", 1, CounterServiceV1.class)));
            hosting.refresh();
            var registry = new HengeServiceRegistry(
                    List.of(HengeServiceDescriptor.of("counter-service", 1, CounterService.class, "counter-service-1")));
            registry.setBeanFactory(hosting);
            var advertiser = new HengeServiceAdvertiser(store, registry, "http://10.0.0.7:8080", Duration.ofSeconds(30), metrics);

            advertiser.start();
            store.failing = true;
            advertiser.renew();
            store.failing = false;
            advertiser.stop();

            assertThat(count("henge.advertisement.renewals", "service", "counter-service", "version", "1", "outcome", "success")).isEqualTo(1);
            assertThat(count("henge.advertisement.renewals", "service", "counter-service", "version", "1", "outcome", "error")).isEqualTo(1);
            assertThat(timings("advertisement", "put", "error")).isEqualTo(1);
        }
    }

    @Test
    void theNodesSeenAdvertisingAreAGaugePerServiceVersionThatFollowsWhatWasLastSeen() {
        metrics.advertisersSeen("echo-service", 1, 3);
        metrics.advertisersSeen("echo-service", 2, 1);
        assertThat(meters.get("henge.service.advertisers").tags("service", "echo-service", "version", "1").gauge().value()).isEqualTo(3);

        metrics.advertisersSeen("echo-service", 1, 0);

        assertThat(meters.get("henge.service.advertisers").tags("service", "echo-service", "version", "1").gauge().value()).isZero();
        assertThat(meters.get("henge.service.advertisers").tags("service", "echo-service", "version", "2").gauge().value()).isEqualTo(1);
    }

    // -- the transport

    private InternalRestTransport transportThatCannotConnect(MockEnvironment environment, AdvertisedEndpoints advertised) {
        RestClient refusing = RestClient.builder().requestFactory((uri, method) -> {
            throw new ConnectException("Connection refused");
        }).build();
        return new InternalRestTransport(refusing, HengeTransportSupport.objectMapper(), new HengeProperties(environment), advertised, metrics);
    }

    private static ServiceInvocation echo() throws NoSuchMethodException {
        return new ServiceInvocation("echo-service", 1, "echo", EchoService.class.getMethod("echo", String.class), new Object[] {"x"});
    }

    @Test
    void aCallThatNeverConnectedIsCountedAsRetriedThenGivenUpOn() throws Exception {
        var transport = transportThatCannotConnect(new MockEnvironment()
                .withProperty("henge.services.echo-service.url", "http://nowhere:1")
                .withProperty("henge.transport.retry.max-attempts", "3")
                .withProperty("henge.transport.retry.backoff", "0"), null);

        assertThatThrownBy(() -> transport.invoke(echo())).isInstanceOf(RemoteServiceException.class);

        assertThat(count("henge.transport.retries", "service", "echo-service", "version", "1", "reason", "connect")).isEqualTo(2);
        assertThat(count("henge.transport.giveups", "service", "echo-service", "version", "1", "reason", "connect")).isEqualTo(1);
        assertThat(count("henge.transport.endpoint.failures")).isZero(); // a configured url isn't an advertised host
    }

    @Test
    void aCallWithRetriesTurnedOffIsGivenUpOnWithoutARetry() throws Exception {
        var transport = transportThatCannotConnect(new MockEnvironment()
                .withProperty("henge.services.echo-service.url", "http://nowhere:1")
                .withProperty("henge.transport.retry.max-attempts", "1"), null);

        assertThatThrownBy(() -> transport.invoke(echo())).isInstanceOf(RemoteServiceException.class);

        assertThat(count("henge.transport.retries", "service", "echo-service", "version", "1", "reason", "connect")).isZero();
        assertThat(count("henge.transport.giveups", "service", "echo-service", "version", "1", "reason", "connect")).isEqualTo(1);
    }

    @Test
    void anAdvertisedHostThatFailsEveryAttemptIsCountedEachTime() throws Exception {
        store.put(ServiceAdvertisement.key("echo-service", 1), HengeServiceAdvertiser.MEMBER,
                new ServiceAdvertisement("http://nowhere:1").encode(), Duration.ofSeconds(30));
        var transport = transportThatCannotConnect(new MockEnvironment()
                .withProperty("henge.transport.retry.max-attempts", "3")
                .withProperty("henge.transport.retry.backoff", "0"),
                new AdvertisedEndpoints(store, Duration.ofSeconds(10), now::get, metrics));

        assertThatThrownBy(() -> transport.invoke(echo())).isInstanceOf(RemoteServiceException.class);

        // The lookup that found the host also counted it, once: the cache served the retries.
        assertThat(meters.get("henge.service.advertisers").tags("service", "echo-service", "version", "1").gauge().value()).isEqualTo(1);
        assertThat(count("henge.transport.endpoint.failures", "service", "echo-service", "version", "1")).isEqualTo(3);
        assertThat(count("henge.transport.giveups", "service", "echo-service", "version", "1", "reason", "connect")).isEqualTo(1);
    }

    @Test
    void channelsAreCountedOpenUntilTheyCloseAndClosedByStatus() {
        metrics.channelOpened("feed-service", 1, "backend");
        metrics.channelOpened("feed-service", 1, "backend");
        metrics.channelOpened("feed-service", 1, "frontend");

        assertThat(meters.get("henge.channels.open").tag("side", "backend").gauge().value()).isEqualTo(2);
        assertThat(meters.get("henge.channels.open").tag("side", "frontend").gauge().value()).isEqualTo(1);

        metrics.channelClosed("feed-service", 1, "backend", 1012);
        metrics.channelClosed("feed-service", 1, "backend", 1000);

        assertThat(meters.get("henge.channels.open").tag("side", "backend").gauge().value()).isZero();
        assertThat(meters.get("henge.channels.closed").tag("status", "1012").tag("service", "feed-service")
                .tag("version", "1").tag("side", "backend").counter().count()).isEqualTo(1);
        assertThat(meters.get("henge.channels.closed").tag("status", "1000").counter().count()).isEqualTo(1);
    }

    @Test
    void trunksAreCountedPerSideAndNotPerBackend() {
        metrics.trunkOpened("frontend");
        metrics.trunkOpened("frontend");
        metrics.trunkOpened("backend");
        metrics.trunkClosed("frontend");

        assertThat(meters.get("henge.trunks.open").tag("side", "frontend").gauge().value()).isEqualTo(1);
        assertThat(meters.get("henge.trunks.open").tag("side", "backend").gauge().value()).isEqualTo(1);
        assertThat(meters.get("henge.trunks.open").gauges()).allSatisfy(gauge ->
                assertThat(gauge.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("side"));
    }
}
