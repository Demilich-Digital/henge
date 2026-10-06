package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

class ClusterSchedulerTest {

    private static final Instant NOON = Instant.parse("2026-10-06T12:00:00Z");

    /** One node's view of a store several nodes share: the store tells members apart by node, this one by name. */
    private record NodeView(SystemEphemeralDatastore shared, String nodeId) implements SystemEphemeralDatastore {
        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            shared.put(key, nodeId + localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            shared.remove(key, nodeId + localName);
        }

        @Override
        public Snapshot read(String key) {
            return shared.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return shared.claim(key, nodeId + localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return shared.tryAcquire(key, amount, limit);
        }
    }

    private static ClusterScheduler.Job job(String name, String cron, Runnable body) {
        return new ClusterScheduler.Job(name, CronExpression.parse(cron), ZoneOffset.UTC, body);
    }

    private static ClusterScheduler node(SystemEphemeralDatastore shared, String id, InstantSource clock, ClusterScheduler.Job job) {
        ClusterScheduler scheduler = new ClusterScheduler(new NodeView(shared, id), clock);
        scheduler.add(job);
        return scheduler;
    }

    @Test
    void ofTwoNodesFiringTheSameInstantExactlyOneRunsIt() {
        var store = new InProcessEphemeralDatastore();
        var runs = new CopyOnWriteArrayList<String>();
        var a = node(store, "a", () -> NOON, job("nightly", "0 0 12 * * *", () -> runs.add("a")));
        var b = node(store, "b", () -> NOON, job("nightly", "0 0 12 * * *", () -> runs.add("b")));

        a.fire(job("nightly", "0 0 12 * * *", () -> runs.add("a")), NOON);
        b.fire(job("nightly", "0 0 12 * * *", () -> runs.add("b")), NOON);

        assertThat(runs).containsExactly("a");
    }

    @Test
    void aFinishedFireStaysTakenSoANodeWithASlowClockCantRunItAgain() {
        var store = new InProcessEphemeralDatastore();
        var runs = new AtomicInteger();
        var job = job("quick", "0 0 12 * * *", runs::incrementAndGet);
        var a = node(store, "a", () -> NOON, job);
        var b = node(store, "b", () -> NOON.plusSeconds(2), job);

        a.fire(job, NOON);
        // a's run is long over; b only now reaches the same fire.
        b.fire(job, NOON);

        assertThat(runs).hasValue(1);
    }

    @Test
    void eachFireIsItsOwnClaim() {
        var store = new InProcessEphemeralDatastore();
        var runs = new AtomicInteger();
        var job = job("hourly", "0 0 * * * *", runs::incrementAndGet);
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOON);
        var a = node(store, "a", now::get, job);

        a.fire(job, NOON);
        now.set(NOON.plusSeconds(3600));
        a.fire(job, NOON.plusSeconds(3600));

        assertThat(runs).hasValue(2);
    }

    @Test
    void differentJobsDontShareAFire() {
        var store = new InProcessEphemeralDatastore();
        var runs = new CopyOnWriteArrayList<String>();
        var one = job("one", "0 0 12 * * *", () -> runs.add("one"));
        var two = job("two", "0 0 12 * * *", () -> runs.add("two"));
        var a = node(store, "a", () -> NOON, one);

        a.fire(one, NOON);
        a.fire(two, NOON);

        assertThat(runs).containsExactly("one", "two");
    }

    @Test
    void aFireThatIsTooLateIsSkippedNotCaughtUp() {
        var store = new InProcessEphemeralDatastore();
        var runs = new AtomicInteger();
        var job = job("late", "0 0 12 * * *", runs::incrementAndGet);
        var a = node(store, "a", () -> NOON.plus(ClusterScheduler.MAX_LATENESS).plusSeconds(1), job);

        a.fire(job, NOON);

        assertThat(runs).hasValue(0);
    }

    @Test
    void aFireJustWithinTheLatenessLimitStillRuns() {
        var store = new InProcessEphemeralDatastore();
        var runs = new AtomicInteger();
        var job = job("late", "0 0 12 * * *", runs::incrementAndGet);
        var a = node(store, "a", () -> NOON.plus(ClusterScheduler.MAX_LATENESS), job);

        a.fire(job, NOON);

        assertThat(runs).hasValue(1);
    }

    @Test
    void aClaimOutlivesTheLatestAnyNodeWillRunTheFire() {
        // The invariant that makes the two limits above safe: nobody runs a fire after its claim has lapsed.
        assertThat(ClusterScheduler.FIRE_TTL).isGreaterThan(ClusterScheduler.MAX_LATENESS);
    }

    @Test
    void aFireIsSkippedWhenTheStoreCantBeAsked() {
        var runs = new AtomicInteger();
        SystemEphemeralDatastore failing = new SystemEphemeralDatastore() {
            @Override
            public String nodeId() {
                return "a";
            }

            @Override
            public void put(String key, String localName, byte[] value, Duration ttl) {
            }

            @Override
            public void remove(String key, String localName) {
            }

            @Override
            public Snapshot read(String key) {
                throw new StoreUnavailableException("away", null);
            }

            @Override
            public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
                throw new StoreUnavailableException("away", null);
            }

            @Override
            public boolean tryAcquire(String key, int amount, RateLimit limit) {
                throw new StoreUnavailableException("away", null);
            }
        };
        var job = job("blind", "0 0 12 * * *", runs::incrementAndGet);
        var scheduler = new ClusterScheduler(failing, () -> NOON);

        scheduler.fire(job, NOON);

        assertThat(runs).hasValue(0);
    }

    @Test
    void aJobThatThrowsDoesntBreakTheSchedulerAndStillCountsAsTheFiresRun() {
        var store = new InProcessEphemeralDatastore();
        var runs = new AtomicInteger();
        var job = job("broken", "0 0 12 * * *", () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        var a = node(store, "a", () -> NOON, job);
        var b = node(store, "b", () -> NOON, job);

        a.fire(job, NOON);
        b.fire(job, NOON);

        // The fire was taken and failed; another node doesn't take it again.
        assertThat(runs).hasValue(1);
    }

    @Test
    void liveNodesOnARealTimerRunEachFireOnce() throws Exception {
        var store = new InProcessEphemeralDatastore();
        List<Instant> ranAt = new CopyOnWriteArrayList<>();
        var jobA = job("every-second", "* * * * * *", () -> ranAt.add(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        var jobB = job("every-second", "* * * * * *", () -> ranAt.add(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        var a = node(store, "a", InstantSource.system(), jobA);
        var b = node(store, "b", InstantSource.system(), jobB);
        try {
            a.start();
            b.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (ranAt.size() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            a.close();
            b.close();
        }

        assertThat(ranAt).hasSizeGreaterThanOrEqualTo(3);
        // Two nodes, one run per second: never two runs in the same second.
        Set<Instant> seconds = new HashSet<>(ranAt);
        assertThat(seconds).hasSameSizeAs(new ArrayList<>(ranAt));
    }
}
