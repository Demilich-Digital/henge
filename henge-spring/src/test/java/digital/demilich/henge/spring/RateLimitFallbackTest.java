package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A rate limiter cut off from the datastore keeps working, in this node's share of the limit. */
class RateLimitFallbackTest {

    private static final RateLimit LIMIT = new RateLimit(4, 4, Duration.ofSeconds(1));

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    /** This node's store, and another node's, as one; an epoch and an outage we can flip. */
    private final InProcessEphemeralDatastore mine = new InProcessEphemeralDatastore(now::get);
    private final InProcessEphemeralDatastore theirs = new InProcessEphemeralDatastore(now::get);
    private volatile String epoch = "epoch-1";
    private volatile boolean down;

    private final SystemEphemeralDatastore store = new SystemEphemeralDatastore() {
        @Override
        public String nodeId() {
            return mine.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            unreachableIfDown();
            mine.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            unreachableIfDown();
            mine.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            unreachableIfDown();
            var members = new HashMap<>(mine.read(key).members());
            members.putAll(theirs.read(key).members());
            return new Snapshot(members, new Epoch(epoch));
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            unreachableIfDown();
            return mine.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            unreachableIfDown();
            return mine.tryAcquire(key, amount, limit);
        }

        private void unreachableIfDown() {
            if (down) {
                throw new StoreUnavailableException("down");
            }
        }
    };

    private final List<String> degraded = new ArrayList<>();
    private final List<Integer> subscribersReported = new ArrayList<>();

    private final SystemMetrics metrics = new SystemMetrics() {
        @Override
        public void rateLimitDegraded(String limit) {
            degraded.add(limit);
        }

        @Override
        public void rateLimitSubscribers(String limit, int nodes) {
            subscribersReported.add(nodes);
        }
    };

    private final HengeBootGate gate = new HengeBootGate(store);
    private final RateLimitSubscriptions subscriptions = new RateLimitSubscriptions(store, Duration.ofSeconds(30), metrics, gate);

    @AfterEach
    void stopHeartbeat() {
        gate.stop();
        subscriptions.destroy();
    }

    private RateLimiter limiter() {
        return RateLimiters.createWithClock("api", LIMIT, store, subscriptions, metrics, now::get);
    }

    private void anotherNodeSubscribes() {
        theirs.put("rate:api", "node", new byte[0], Duration.ofSeconds(30));
        subscriptions.renewAll();
    }

    private static int granted(RateLimiter limiter, int attempts) {
        int granted = 0;
        for (int i = 0; i < attempts; i++) {
            granted += limiter.tryAcquire() ? 1 : 0;
        }
        return granted;
    }

    @Test
    void whileTheStoreIsThereTheSharedBucketDecides() {
        RateLimiter limiter = limiter();

        assertThat(granted(limiter, 10)).isEqualTo(4);
        assertThat(degraded).isEmpty();
    }

    @Test
    void aNodeCountsItselfAndTheOthersThatBuiltTheLimiter() {
        limiter();
        assertThat(subscribersReported).containsExactly(1);

        anotherNodeSubscribes();

        assertThat(subscribersReported).containsExactly(1, 2);
    }

    @Test
    void withoutTheStoreANodeDrawsOnItsOwnShareOfTheLimit() {
        RateLimiter limiter = limiter();
        anotherNodeSubscribes();
        down = true;

        // A burst of 4 and 4 a second, over two nodes: 2 and 2 every second.
        assertThat(granted(limiter, 10)).isEqualTo(2);
        assertThat(degraded).hasSize(10);

        now.updateAndGet(t -> t.plusSeconds(1));
        assertThat(granted(limiter, 10)).isEqualTo(2);
    }

    @Test
    void eachSubjectGetsItsOwnShareToo() {
        RateLimiter limiter = limiter();
        anotherNodeSubscribes();
        down = true;

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void theSharedBucketIsBackAsSoonAsTheStoreAnswers() {
        RateLimiter limiter = limiter();
        down = true;
        granted(limiter, 10);
        degraded.clear();

        down = false;

        assertThat(granted(limiter, 10)).isEqualTo(4);
        assertThat(degraded).isEmpty();
    }

    @Test
    void aCountFromAWipedStoreIsNotBelievedUntilTheNextHeartbeatConfirmsIt() {
        limiter();
        anotherNodeSubscribes();

        theirs.remove("rate:api", "node");
        epoch = "epoch-2";
        subscriptions.renewAll();
        assertThat(subscribersReported).last().isEqualTo(2);

        subscriptions.renewAll();
        assertThat(subscribersReported).last().isEqualTo(1);
    }

    @Test
    void aCountFromTheSameEpochIsBelievedAtOnce() {
        limiter();
        anotherNodeSubscribes();

        theirs.remove("rate:api", "node");
        subscriptions.renewAll();

        assertThat(subscribersReported).last().isEqualTo(1);
    }

    @Test
    void aStoreThatFailsAHeartbeatKeepsTheLastCount() {
        RateLimiter limiter = limiter();
        anotherNodeSubscribes();
        down = true;

        subscriptions.renewAll();

        assertThat(granted(limiter, 10)).isEqualTo(2);
    }

    @Test
    void aNodeThatCantReachTheStoreRefusesAndIsNotReadyUntilItHas() throws Exception {
        down = true;
        RateLimiter limiter = limiter();
        gate.start();

        assertThat(gate.isReady()).isFalse();
        assertThatThrownBy(limiter::tryAcquire).isInstanceOf(StoreUnavailableException.class);
        assertThat(degraded).isEmpty(); // no share to take: it has never read N

        down = false;
        for (int i = 0; i < 100 && !gate.isReady(); i++) {
            Thread.sleep(50);
        }
        assertThat(gate.isReady()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(subscribersReported).last().isEqualTo(1);
    }

    @Test
    void leavingTakesThisNodeOutOfTheCount() {
        limiter();
        assertThat(store.read("rate:api").members()).hasSize(1);

        subscriptions.destroy();

        assertThat(store.read("rate:api").members()).isEmpty();
    }

    @Test
    void theSubscribersAreMembersOfTheLimitersKeyAndNotItsBucket() {
        RateLimiter limiter = limiter();

        assertThat(granted(limiter, 10)).isEqualTo(4);
        assertThat(store.read("rate:api").members()).hasSize(1);
    }

    @Test
    void aShareSpreadsTheRateOverAPeriodNodesTimesAsLongAndDividesTheBurst() {
        assertThat(RateLimiters.share(LIMIT, 1)).isEqualTo(LIMIT);
        assertThat(RateLimiters.share(LIMIT, 4)).isEqualTo(new RateLimit(1, 4, Duration.ofSeconds(4)));
        assertThat(RateLimiters.share(new RateLimit(10, 6, Duration.ofSeconds(1)), 3)).isEqualTo(new RateLimit(3, 6, Duration.ofSeconds(3)));
    }

    @Test
    void aBurstSmallerThanTheNodesStillLetsEachNodeOneThrough() {
        assertThat(RateLimiters.share(new RateLimit(2, 10, Duration.ofSeconds(1)), 5).capacity()).isEqualTo(1);
    }

    @Test
    void pastTheLongestPeriodThePermitsAreDividedInstead() {
        RateLimit perHour = new RateLimit(50, 100, Duration.ofHours(1));

        assertThat(RateLimiters.share(perHour, 10)).isEqualTo(new RateLimit(5, 10, Duration.ofHours(1)));
        assertThat(RateLimiters.share(new RateLimit(50, 5, Duration.ofHours(1)), 10).permits()).isEqualTo(1);
    }
}
