package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.Epoch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class HengeLeasePollerTest {

    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofMinutes(5);
    private static final Duration TTL = Duration.ofSeconds(30);

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(Instant::now);
    private final HengeLeaseKeeper keeper = new HengeLeaseKeeper(store, TTL, null);
    private final HengeLeasePoller poller = new HengeLeasePoller(store, keeper, INTERVAL, MAX, TTL,
            new DefaultListableBeanFactory().getBeanProvider(HengeBootGate.class));
    private final AtomicInteger hosted = new AtomicInteger();

    @AfterEach
    void close() {
        keeper.destroy();
    }

    /** Far enough ahead that every candidate is due, whatever its jitter or backoff; each round is past the last. */
    private Instant later(int round) {
        return Instant.now().plus(MAX.multipliedBy(4L * round));
    }

    private Instant later() {
        return later(1);
    }

    private HengeLeasePoller.Candidate candidate(String name, LeaseNeed... needs) {
        return new HengeLeasePoller.Candidate(name, List.of(needs), () -> {
            if (keeper.acquireAll(name, List.of(needs)) != null) {
                return false;
            }
            hosted.incrementAndGet();
            return true;
        });
    }

    @Test
    void aFullLeaseIsNotClaimedAndItsCandidateKeepsWaiting() {
        store.claim("lease:db", "other-node", 10, 10, TTL.multipliedBy(100));
        poller.add(candidate("a@1", new LeaseNeed("db", 5, 10)));

        poller.tick(later());

        assertThat(hosted).hasValue(0);
        assertThat(poller.candidates()).isEqualTo(1);
    }

    @Test
    void roomThatAppearsLaterIsTakenUp() {
        store.claim("lease:db", "other-node", 10, 10, TTL.multipliedBy(100));
        poller.add(candidate("a@1", new LeaseNeed("db", 5, 10)));
        poller.tick(later());

        store.remove("lease:db", "other-node");
        poller.tick(later(2));

        assertThat(hosted).hasValue(1);
        assertThat(poller.candidates()).isZero();
        assertThat(keeper.isHeld("db")).isTrue();
    }

    @Test
    void nothingIsAttemptedBeforeACandidateIsDue() {
        poller.add(candidate("a@1", new LeaseNeed("db", 5, 10)));

        poller.tick(Instant.now());

        assertThat(hosted).hasValue(0);
        assertThat(poller.candidates()).isEqualTo(1);
    }

    @Test
    void aSetWithOneFullLeaseIsNeverPartlyClaimed() {
        // 'cache' has room, 'db' doesn't: the read of 'db' stops the attempt before 'cache' is written to.
        store.claim("lease:db", "other-node", 10, 10, TTL.multipliedBy(100));
        poller.add(candidate("a@1", new LeaseNeed("cache", 5, 10), new LeaseNeed("db", 5, 10)));

        poller.tick(later());

        assertThat(store.read("lease:cache").members()).isEmpty();
        assertThat(hosted).hasValue(0);
    }

    @Test
    void aLeaseThisNodeAlreadyHoldsNeedsNoRoomInTheCluster() {
        assertThat(keeper.acquireAll("held@1", List.of(new LeaseNeed("db", 10, 10)))).isNull();
        poller.add(candidate("a@1", new LeaseNeed("db", 10, 10)));

        poller.tick(later());

        assertThat(hosted).hasValue(1);
    }

    @Test
    void aFailureToHostIsARefusalNotACrash() {
        poller.add(new HengeLeasePoller.Candidate("a@1", List.of(new LeaseNeed("db", 5, 10)), () -> {
            throw new IllegalStateException("the implementation failed to build");
        }));

        poller.tick(later());

        assertThat(poller.candidates()).isEqualTo(1);
    }

    /** Reads from a storage whose epoch is whatever the test says: a restarted or wiped store. */
    private static final class Wipeable implements SystemEphemeralDatastore {
        final InProcessEphemeralDatastore delegate;
        volatile Epoch epoch = new Epoch("first");

        Wipeable(InProcessEphemeralDatastore delegate) {
            this.delegate = delegate;
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
            return new Snapshot(delegate.read(key).members(), epoch);
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

    @Test
    void afterTheStoreIsWipedNoRoomIsTakenUntilTheHoldersHaveHadTimeToSayTheyAreStillThere() {
        var wipeable = new Wipeable(store);
        var keeperOnWipeable = new HengeLeaseKeeper(wipeable, TTL, null);
        var careful = new HengeLeasePoller(wipeable, keeperOnWipeable, INTERVAL, MAX, TTL,
                new DefaultListableBeanFactory().getBeanProvider(HengeBootGate.class));
        try {
            // Full at first, so a look is made and the epoch is seen.
            store.claim("lease:db", "other-process", 10, 10, TTL.multipliedBy(100));
            var hostedHere = new AtomicInteger();
            careful.add(new HengeLeasePoller.Candidate("a@1", List.of(new LeaseNeed("db", 5, 10)), () -> {
                hostedHere.incrementAndGet();
                return keeperOnWipeable.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10))) == null;
            }));
            careful.tick(later(1));
            assertThat(hostedHere).hasValue(0);

            // The store restarts and is empty: the holder has not renewed yet, and the room is not real.
            store.remove("lease:db", "other-process");
            wipeable.epoch = new Epoch("second");
            careful.tick(later(2));
            assertThat(hostedHere).hasValue(0);
            assertThat(careful.candidates()).isEqualTo(1);

            // A TTL later, with the holder back (it renewed), the lease really is full, and it still is.
            store.claim("lease:db", "other-process", 10, 10, TTL.multipliedBy(100));
            careful.tick(later(3));
            assertThat(hostedHere).hasValue(0);

            // And when there is room after all, it is taken.
            store.remove("lease:db", "other-process");
            careful.tick(later(4));
            assertThat(hostedHere).hasValue(1);
        } finally {
            keeperOnWipeable.destroy();
        }
    }

    @Test
    void jitterStaysWithinAHalfEitherWay() {
        for (int i = 0; i < 1000; i++) {
            assertThat(HengeLeasePoller.jittered(INTERVAL)).isBetween(INTERVAL.dividedBy(2), INTERVAL.multipliedBy(3).dividedBy(2));
        }
    }
}
