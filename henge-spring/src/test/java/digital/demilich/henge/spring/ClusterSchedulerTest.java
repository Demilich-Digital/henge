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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
        return job(name, cron, body, Duration.ofHours(1), false);
    }

    private static ClusterScheduler.Job job(String name, String cron, Runnable body, Duration maxRuntime, boolean overlap) {
        return new ClusterScheduler.Job(name, CronExpression.parse(cron), ZoneOffset.UTC, body, maxRuntime, overlap);
    }

    /** A body that holds the run open until released, and says whether it was interrupted. */
    private static final class Blocked implements Runnable {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void run() {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
    }

    private static Thread inBackground(Runnable fire) {
        Thread thread = new Thread(fire);
        thread.start();
        return thread;
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
    void aFireThatFindsTheLastRunStillGoingIsSkippedOnEveryNodeNotQueued() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var long1 = job("long", "0 0 * * * *", blocked);
        var a = node(store, "a", () -> NOON.plusSeconds(3600), long1);
        var otherRuns = new AtomicInteger();
        var longOnB = job("long", "0 0 * * * *", otherRuns::incrementAndGet);
        var b = node(store, "b", () -> NOON.plusSeconds(3600), longOnB);

        Thread first = inBackground(() -> a.fire(long1, NOON.plusSeconds(3600)));
        assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
        // The next hour's fire arrives while the first run is going, on the same node and on another.
        var next = NOON.plusSeconds(7200);
        var aLater = new ClusterScheduler(new NodeView(store, "a"), () -> next);
        aLater.add(long1);
        aLater.fire(long1, next);
        var bLater = new ClusterScheduler(new NodeView(store, "b"), () -> next);
        bLater.add(longOnB);
        bLater.fire(longOnB, next);

        assertThat(blocked.runs).hasValue(1);
        assertThat(otherRuns).hasValue(0);
        blocked.release.countDown();
        first.join(5000);
        // Once it has ended the job is free again, and a later fire runs.
        var after = NOON.plusSeconds(10800);
        var bAfter = new ClusterScheduler(new NodeView(store, "b"), () -> after);
        bAfter.add(longOnB);
        bAfter.fire(longOnB, after);
        assertThat(otherRuns).hasValue(1);
    }

    @Test
    void aFinishedRunHandsBackItsClaim() {
        var store = new InProcessEphemeralDatastore();
        var job = job("short", "0 0 12 * * *", () -> {
        });
        var a = node(store, "a", () -> NOON, job);

        a.fire(job, NOON);

        assertThat(store.read(ClusterScheduler.runKey(job, NOON)).members()).isEmpty();
    }

    @Test
    void aFailedRunHandsBackItsClaimToo() {
        var store = new InProcessEphemeralDatastore();
        var job = job("failing", "0 0 12 * * *", () -> {
            throw new IllegalStateException("boom");
        });
        var a = node(store, "a", () -> NOON, job);

        a.fire(job, NOON);

        assertThat(store.read(ClusterScheduler.runKey(job, NOON)).members()).isEmpty();
    }

    @Test
    void aJobThatAllowsOverlapStartsWhileTheLastRunIsStillGoing() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var overlapping = job("overlapping", "0 0 * * * *", blocked, Duration.ofHours(1), true);
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOON.plusSeconds(3600));
        var a = node(store, "a", now::get, overlapping);

        Thread first = inBackground(() -> a.fire(overlapping, NOON.plusSeconds(3600)));
        assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
        now.set(NOON.plusSeconds(7200));
        Thread second = inBackground(() -> a.fire(overlapping, NOON.plusSeconds(7200)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (blocked.runs.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(blocked.runs).hasValue(2);
        blocked.release.countDown();
        first.join(5000);
        second.join(5000);
    }

    @Test
    void eachOverlappingRunIsVisibleUnderItsOwnClaimAndHandsItBack() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var overlapping = job("overlapping", "0 0 * * * *", blocked, Duration.ofHours(1), true);
        var first = NOON.plusSeconds(3600);
        var second = NOON.plusSeconds(7200);
        var now = new java.util.concurrent.atomic.AtomicReference<>(first);
        var a = node(store, "a", now::get, overlapping);

        Thread one = inBackground(() -> a.fire(overlapping, first));
        assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
        now.set(second);
        Thread two = inBackground(() -> a.fire(overlapping, second));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (blocked.runs.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(store.read(ClusterScheduler.runKey(overlapping, first)).members()).hasSize(1);
        assertThat(store.read(ClusterScheduler.runKey(overlapping, second)).members()).hasSize(1);
        blocked.release.countDown();
        one.join(5000);
        two.join(5000);
        assertThat(store.read(ClusterScheduler.runKey(overlapping, first)).members()).isEmpty();
        assertThat(store.read(ClusterScheduler.runKey(overlapping, second)).members()).isEmpty();
    }

    @Test
    void ofTwoNodesThatBothWonAnOverlappingFireOnlyOneRunsIt() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var overlapping = job("overlapping", "0 0 12 * * *", blocked, Duration.ofHours(1), true);
        var otherRuns = new AtomicInteger();
        var onB = job("overlapping", "0 0 12 * * *", otherRuns::incrementAndGet, Duration.ofHours(1), true);
        var a = node(store, "a", () -> NOON, overlapping);
        var b = node(store, "b", () -> NOON, onB);

        Thread run = inBackground(() -> a.fire(overlapping, NOON));
        assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
        // The fire's claim is lost (a failover), so b wins the same fire too.
        var fireMembers = store.read(ClusterScheduler.fireKey(overlapping, NOON)).members();
        store.remove(ClusterScheduler.fireKey(overlapping, NOON), fireMembers.keySet().iterator().next().localName());
        b.fire(onB, NOON);

        assertThat(otherRuns).hasValue(0);
        blocked.release.countDown();
        run.join(5000);
    }

    @Test
    void aRunPastItsMaxRuntimeIsInterruptedAndStopsHoldingTheJob() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var hung = job("hung", "0 0 12 * * *", blocked, Duration.ofMillis(200), false);
        var a = new ClusterScheduler(new NodeView(store, "a"), () -> NOON, Duration.ofMillis(300));
        a.add(hung);
        try {
            Thread run = inBackground(() -> a.fire(hung, NOON));
            assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
            run.join(5000);

            assertThat(blocked.interrupted).isTrue();
            assertThat(run.isAlive()).isFalse();
        } finally {
            a.close();
        }
    }

    @Test
    void aRunThatIgnoresItsInterruptStopsRenewingSoTheNextFireCanStart() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var stuck = new CountDownLatch(1);
        var releaseStuck = new CountDownLatch(1);
        Runnable ignoresInterrupts = () -> {
            stuck.countDown();
            while (true) {
                try {
                    releaseStuck.await();
                    return;
                } catch (InterruptedException ignored) {
                    // Carries on, as a hung call would.
                }
            }
        };
        var hung = job("stubborn", "0 0 * * * *", ignoresInterrupts, Duration.ofMillis(100), false);
        var a = new ClusterScheduler(new NodeView(store, "a"), () -> NOON.plusSeconds(3600), Duration.ofMillis(300));
        a.add(hung);
        var nextRuns = new AtomicInteger();
        var onB = job("stubborn", "0 0 * * * *", nextRuns::incrementAndGet);
        var next = NOON.plusSeconds(7200);
        var b = new ClusterScheduler(new NodeView(store, "b"), () -> next);
        b.add(onB);
        try {
            inBackground(() -> a.fire(hung, NOON.plusSeconds(3600)));
            assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();

            // Its claim, no longer renewed, lapses within its TTL; until then the job counts as running.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (nextRuns.get() == 0 && System.nanoTime() < deadline) {
                b.fire(onB, next);
                Thread.sleep(50);
            }
            assertThat(nextRuns).hasValue(1);
        } finally {
            releaseStuck.countDown();
            a.close();
            b.close();
        }
    }

    @Test
    void aRunThatLosesItsClaimToAnotherNodeIsInterrupted() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var blocked = new Blocked();
        var job = job("usurped", "0 0 12 * * *", blocked);
        var a = new ClusterScheduler(new NodeView(store, "a"), () -> NOON, Duration.ofMillis(300));
        a.add(job);
        try {
            Thread run = inBackground(() -> a.fire(job, NOON));
            assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
            // The claim lapses (a partition, a wipe) and another node is given the job.
            var members = store.read(ClusterScheduler.runKey(job, NOON)).members();
            assertThat(members).hasSize(1);
            store.remove(ClusterScheduler.runKey(job, NOON), members.keySet().iterator().next().localName());
            assertThat(store.claim(ClusterScheduler.runKey(job, NOON), "bnode", 1, 1, Duration.ofMinutes(5))).isTrue();

            run.join(5000);

            assertThat(blocked.interrupted).isTrue();
        } finally {
            a.close();
        }
    }

    @Test
    void aRunIsLeftGoingWhenTheStoreIsAwayAtItsRenewal() throws Exception {
        var store = new InProcessEphemeralDatastore();
        var away = new AtomicBoolean();
        var blocked = new Blocked();
        var job = job("steady", "0 0 12 * * *", blocked);
        var flaky = new FlakyView(store, "a", away);
        var a = new ClusterScheduler(flaky, () -> NOON, Duration.ofMillis(300));
        a.add(job);
        try {
            Thread run = inBackground(() -> a.fire(job, NOON));
            assertThat(blocked.started.await(5, TimeUnit.SECONDS)).isTrue();
            away.set(true);
            Thread.sleep(700);

            // Nothing else can have been given the job without the store, so it keeps going.
            assertThat(blocked.interrupted).isFalse();
            assertThat(run.isAlive()).isTrue();
            blocked.release.countDown();
            run.join(5000);
        } finally {
            a.close();
        }
    }

    /** A node's view of the store that fails every claim while {@code away} is set. */
    private static final class FlakyView implements SystemEphemeralDatastore {
        private final NodeView view;
        private final AtomicBoolean away;

        FlakyView(SystemEphemeralDatastore shared, String id, AtomicBoolean away) {
            this.view = new NodeView(shared, id);
            this.away = away;
        }

        @Override
        public String nodeId() {
            return view.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            view.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            view.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return view.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            if (away.get()) {
                throw new StoreUnavailableException("away", null);
            }
            return view.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            return view.tryAcquire(key, amount, limit);
        }
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
