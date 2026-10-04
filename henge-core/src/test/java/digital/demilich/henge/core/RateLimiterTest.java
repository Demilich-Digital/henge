package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);

    /** 10 permits a second, bursting to 5. */
    private final RateLimit limit = RateLimit.perSecond(10, 5);

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    private RateLimiter limiter(String key) {
        return new RateLimiter(store, key, limit);
    }

    @Test
    void aQuietBucketAbsorbsABurstUpToItsCapacityThenRefuses() {
        var limiter = limiter("api");

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).as("permit %d", i).isTrue();
        }

        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void thePermitsLeakBackAtTheSustainedRate() {
        var limiter = limiter("api");
        limiter.tryAcquire(5);

        advance(Duration.ofMillis(99));
        assertThat(limiter.tryAcquire()).isFalse();
        advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void eachSubjectHasItsOwnBucketApartFromTheWholeLimiters() {
        var limiter = limiter("api");

        assertThat(limiter.tryAcquire("alice", 5)).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();

        assertThat(limiter.tryAcquire("bob", 5)).isTrue();
        assertThat(limiter.tryAcquire(5)).isTrue();
    }

    @Test
    void aRefusedRequestChangesNothing() {
        var limiter = limiter("api");
        limiter.tryAcquire(4);

        assertThat(limiter.tryAcquire(2)).isFalse();

        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void aBucketLeftAloneRefillsToCapacityButNoFurther() {
        var limiter = limiter("api");
        limiter.tryAcquire(5);

        advance(Duration.ofDays(3));

        assertThat(limiter.tryAcquire(5)).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void aRequestLargerThanTheCapacityIsNeverGranted() {
        assertThat(limiter("api").tryAcquire(6)).isFalse();
        advance(Duration.ofDays(1));
        assertThat(limiter("api").tryAcquire(6)).isFalse();
    }

    @Test
    void bucketsAreIndependentByKey() {
        limiter("a").tryAcquire(5);

        assertThat(limiter("a").tryAcquire()).isFalse();
        assertThat(limiter("b").tryAcquire()).isTrue();
    }

    @Test
    void aSlowRateLeaksExactlyEvenWhenPolledOften() {
        var slow = new RateLimiter(store, "slow", new RateLimit(1, 1, Duration.ofMinutes(1)));
        assertThat(slow.tryAcquire()).isTrue();

        // Polling every millisecond must not lose the fractions that add up to a permit.
        for (int i = 0; i < 59_999; i++) {
            advance(Duration.ofMillis(1));
            assertThat(slow.tryAcquire()).isFalse();
        }
        advance(Duration.ofMillis(1));

        assertThat(slow.tryAcquire()).isTrue();
    }

    @Test
    void aClockThatStepsBackwardsDoesNotRefillTheBucket() {
        var limiter = limiter("api");
        limiter.tryAcquire(5);

        advance(Duration.ofSeconds(-10));

        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void aBucketAndMembersMayShareAKey() {
        store.claim("shared", "a", 1, 5, Duration.ofSeconds(30));

        assertThat(limiter("shared").tryAcquire(5)).isTrue();
        assertThat(store.read("shared").members()).hasSize(1);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> limiter("api").tryAcquire(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(0, 1, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, 0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, 1, Duration.ofNanos(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, 1, Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCallersNeverGetMoreThanTheCapacity() throws Exception {
        int callers = 64;
        var limiter = limiter("api");
        var executor = Executors.newFixedThreadPool(callers);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < callers; i++) {
                Callable<Boolean> call = () -> {
                    start.await();
                    return limiter.tryAcquire();
                };
                results.add(executor.submit(call));
            }
            start.countDown();
            int granted = 0;
            for (var result : results) {
                if (result.get()) {
                    granted++;
                }
            }

            assertThat(granted).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }
}
