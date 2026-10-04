package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdvertisedEndpointsTest {

    private static final Duration REFRESH = Duration.ofSeconds(10);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    /** Two nodes' worth of advertisements on one store, and an epoch and failure we can flip. */
    private final InProcessEphemeralDatastore inner = new InProcessEphemeralDatastore(now::get);
    private final InProcessEphemeralDatastore other = new InProcessEphemeralDatastore(now::get);
    private volatile String epoch = "epoch-1";
    private volatile boolean failing;

    private final SystemEphemeralDatastore store = new SystemEphemeralDatastore() {
        @Override
        public String nodeId() {
            return inner.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            inner.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            inner.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            if (failing) {
                throw new IllegalStateException("datastore is down");
            }
            var members = new java.util.HashMap<>(inner.read(key).members());
            members.putAll(other.read(key).members());
            return new Snapshot(members, new Epoch(epoch));
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return inner.claim(key, localName, amount, capacity, ttl);
        }
    };

    private final AdvertisedEndpoints endpoints = new AdvertisedEndpoints(store, REFRESH, now::get);

    private void advertise(InProcessEphemeralDatastore node, String url) {
        node.put(ServiceAdvertisement.key("echo-service", 1), "host", new ServiceAdvertisement(url).encode(), Duration.ofHours(1));
    }

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    @Test
    void nobodyAdvertisingMeansNoEndpoint() {
        assertThat(endpoints.next("echo-service", 1)).isNull();
    }

    @Test
    void anAdvertisedUrlIsReturnedForExactlyThatServiceVersion() {
        advertise(inner, "http://a:8080");

        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");
        assertThat(endpoints.next("echo-service", 2)).isNull();
        assertThat(endpoints.next("other-service", 1)).isNull();
    }

    @Test
    void callsRotateThroughEveryAdvertisingNode() {
        advertise(inner, "http://a:8080");
        advertise(other, "http://b:8080");

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            seen.add(endpoints.next("echo-service", 1));
        }

        assertThat(seen).containsExactlyInAnyOrder("http://a:8080", "http://b:8080");
    }

    @Test
    void aNodeWithNoUrlIsNeverChosen() {
        advertise(inner, null);
        advertise(other, "http://b:8080");

        for (int i = 0; i < 4; i++) {
            assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://b:8080");
        }
    }

    @Test
    void theDatastoreIsReadOncePerIntervalNotPerCall() {
        advertise(inner, "http://a:8080");
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        advertise(other, "http://b:8080");
        advance(Duration.ofSeconds(9));
        for (int i = 0; i < 4; i++) {
            assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080"); // the cache, still fresh
        }

        advance(Duration.ofSeconds(2));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            seen.add(endpoints.next("echo-service", 1));
        }
        assertThat(seen).containsExactlyInAnyOrder("http://a:8080", "http://b:8080");
    }

    @Test
    void aWithdrawnNodeDisappearsOnceTheCacheIsStale() {
        advertise(inner, "http://a:8080");
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        inner.remove(ServiceAdvertisement.key("echo-service", 1), "host");
        advance(REFRESH.plusSeconds(1));

        assertThat(endpoints.next("echo-service", 1)).isNull();
    }

    @Test
    void anEmptyReadFromANewEpochKeepsTheLastKnownEndpointsForOneMoreInterval() {
        advertise(inner, "http://a:8080");
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        // The store is wiped and restarted: nothing advertised, and a different epoch.
        inner.remove(ServiceAdvertisement.key("echo-service", 1), "host");
        epoch = "epoch-2";
        advance(REFRESH.plusSeconds(1));
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        // Still empty a heartbeat later, now from the same epoch: believed.
        advance(REFRESH.plusSeconds(1));
        assertThat(endpoints.next("echo-service", 1)).isNull();
    }

    @Test
    void anEmptyReadFromTheSameEpochIsBelievedAtOnce() {
        advertise(inner, "http://a:8080");
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        inner.remove(ServiceAdvertisement.key("echo-service", 1), "host");
        advance(REFRESH.plusSeconds(1));

        assertThat(endpoints.next("echo-service", 1)).isNull();
    }

    @Test
    void aDatastoreThatCantBeReadKeepsServingWhatWasCachedAndRetriesNextCall() {
        advertise(inner, "http://a:8080");
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        failing = true;
        advance(REFRESH.plusSeconds(1));
        assertThat(endpoints.next("echo-service", 1)).isEqualTo("http://a:8080");

        failing = false;
        advertise(other, "http://b:8080");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            seen.add(endpoints.next("echo-service", 1));
        }
        assertThat(seen).contains("http://b:8080"); // no waiting out another interval
    }

    @Test
    void aDatastoreThatCantBeReadAndHasNothingCachedFailsTheCall() {
        failing = true;

        assertThatThrownBy(() -> endpoints.next("echo-service", 1)).hasMessage("datastore is down");
    }
}
