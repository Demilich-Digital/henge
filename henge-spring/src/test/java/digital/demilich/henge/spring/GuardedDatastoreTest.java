package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GuardedDatastoreTest {

    private static final Duration INITIAL = Duration.ofMillis(500);
    private static final Duration MAX = Duration.ofSeconds(2);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final AtomicInteger calls = new AtomicInteger();
    private boolean down;
    private Runnable onReach = () -> { };
    private final SystemEphemeralDatastore inner = new InProcessEphemeralDatastore(now::get);
    private final SystemEphemeralDatastore flaky = new FlakyStore();
    private final GuardedDatastore guarded = new GuardedDatastore(flaky, INITIAL, MAX, now::get);

    /** The in-process store, until {@code down}. Counts what reaches it. */
    private final class FlakyStore implements SystemEphemeralDatastore {
        public String nodeId() {
            return inner.nodeId();
        }

        public void put(String key, String localName, byte[] value, Duration ttl) {
            reached();
            inner.put(key, localName, value, ttl);
        }

        public void remove(String key, String localName) {
            reached();
            inner.remove(key, localName);
        }

        public Snapshot read(String key) {
            reached();
            return inner.read(key);
        }

        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            reached();
            return inner.claim(key, localName, amount, capacity, ttl);
        }

        public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
            reached();
            return inner.tryAcquire(key, amount, limit);
        }

        private void reached() {
            calls.incrementAndGet();
            onReach.run();
            if (down) {
                throw new IllegalStateException("connection refused");
            }
        }
    }

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    @Test
    void aReachableStoreIsJustUsed() {
        guarded.put("k", "m", new byte[] {1}, Duration.ofSeconds(30));

        assertThat(guarded.read("k").members()).hasSize(1);
        assertThat(guarded.nodeId()).isEqualTo(inner.nodeId());
    }

    @Test
    void theFirstFailureIsReportedAsTheStoreBeingUnavailableWithItsCause() {
        down = true;

        assertThatThrownBy(() -> guarded.read("k"))
                .isInstanceOf(StoreUnavailableException.class)
                .hasRootCauseMessage("connection refused");
    }

    @Test
    void afterAFailureCallsFailAtOnceWithoutReachingTheStoreUntilTheBackoffPasses() {
        down = true;
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        calls.set(0);

        advance(INITIAL.minusMillis(1));
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThatThrownBy(() -> guarded.claim("l", "m", 1, 1, Duration.ofSeconds(30))).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).hasValue(0);

        advance(Duration.ofMillis(1));
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).hasValue(1); // the probe, and only that
    }

    private void assertBackoffOf(Duration expected) {
        calls.set(0);
        advance(expected.minusMillis(1));
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).as("still backing off a millisecond early").hasValue(0);

        advance(Duration.ofMillis(1));
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).as("the probe reaches the store, and fails").hasValue(1);
    }

    @Test
    void theBackoffDoublesWithEachFailureUpToTheCap() {
        down = true;
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);

        assertBackoffOf(Duration.ofMillis(500));
        assertBackoffOf(Duration.ofMillis(1000));
        assertBackoffOf(Duration.ofMillis(2000));
        assertBackoffOf(MAX);
    }

    @Test
    void onlyOneCallProbesTheStoreAtATime() {
        down = true;
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        advance(INITIAL);
        calls.set(0);
        AtomicInteger reachedByOthers = new AtomicInteger();
        onReach = () -> {
            onReach = () -> { };
            int before = calls.get();
            assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
            reachedByOthers.set(calls.get() - before);
        };

        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class); // the probe

        assertThat(reachedByOthers).hasValue(0);
    }

    @Test
    void aSuccessEndsTheOutageAndTheNextFailureStartsTheBackoffAgain() {
        down = true;
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        advance(INITIAL);
        down = false;
        guarded.read("k");

        down = true;
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        calls.set(0);
        advance(INITIAL.minusMillis(1)); // the first backoff again, not a continuation of the last outage's
        assertThatThrownBy(() -> guarded.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void aWipedStoreComingBackIsReachableAtOnce() {
        down = true;
        assertThatThrownBy(() -> guarded.put("k", "m", new byte[] {1}, Duration.ofSeconds(30))).isInstanceOf(StoreUnavailableException.class);
        advance(INITIAL);
        down = false;

        guarded.put("k", "m", new byte[] {1}, Duration.ofSeconds(30));

        assertThat(guarded.read("k").members()).hasSize(1);
    }

    @Test
    void aCallersMistakeIsNotTheStoresFailure() {
        assertThatThrownBy(() -> guarded.claim("k", "m", 1, -1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);

        calls.set(0);
        guarded.read("k");
        assertThat(calls).hasValue(1);
    }

    @Test
    void aZeroBackoffNeverFailsFast() {
        GuardedDatastore none = new GuardedDatastore(flaky, Duration.ZERO, Duration.ZERO, now::get);
        down = true;
        assertThatThrownBy(() -> none.read("k")).isInstanceOf(StoreUnavailableException.class);
        calls.set(0);

        assertThatThrownBy(() -> none.read("k")).isInstanceOf(StoreUnavailableException.class);
        assertThat(calls).hasValue(1);
    }
}
