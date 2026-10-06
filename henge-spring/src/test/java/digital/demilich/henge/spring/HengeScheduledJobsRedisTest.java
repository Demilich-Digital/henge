package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.HengeScheduled;
import digital.demilich.henge.redis.RedisEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.support.CronExpression;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code @HengeScheduled} across nodes that really are separate: each is its own connection to a real Redis, with
 * its own node id, so that "once per fire" rests on Redis's atomic claims and its own clock for expiry and not on
 * a fake that merely behaves.
 */
@Testcontainers(disabledWithoutDocker = true)
class HengeScheduledJobsRedisTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private static final Instant NOON = Instant.parse("2026-10-06T12:00:00Z");

    private static String uri;
    private static RedisEphemeralDatastore nodeA;
    private static RedisEphemeralDatastore nodeB;

    @BeforeAll
    static void connect() {
        uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        nodeA = RedisEphemeralDatastore.connect(uri);
        nodeB = RedisEphemeralDatastore.connect(uri);
    }

    @AfterAll
    static void disconnect() {
        nodeA.close();
        nodeB.close();
    }

    private static ClusterScheduler.Job job(String name, Runnable body, Duration maxRuntime, boolean overlap) {
        return new ClusterScheduler.Job(name, CronExpression.parse("0 0 * * * *"), ZoneOffset.UTC, body, maxRuntime, overlap);
    }

    /** A name no other test has used, so the keys left in the shared Redis by an earlier test can't matter. */
    private static String unique(String base) {
        return base + "-" + System.nanoTime();
    }

    private static Thread inBackground(Runnable run) {
        Thread thread = new Thread(run);
        thread.start();
        return thread;
    }

    @Test
    void ofTwoNodesFiringTheSameInstantOnRedisExactlyOneRunsIt() {
        var runs = new CopyOnWriteArrayList<String>();
        var onA = job(unique("once"), () -> runs.add("a"), Duration.ofHours(1), false);
        var onB = new ClusterScheduler.Job(onA.name(), onA.cron(), onA.zone(), () -> runs.add("b"), Duration.ofHours(1), false);
        var a = new ClusterScheduler(nodeA, () -> NOON);
        var b = new ClusterScheduler(nodeB, () -> NOON);

        a.fire(onA, NOON);
        b.fire(onB, NOON);

        assertThat(runs).containsExactly("a");
        a.close();
        b.close();
    }

    @Test
    void aFireStaysTakenAfterTheRunEndsSoALateNodeCantRunItAgain() {
        var runs = new AtomicInteger();
        var onA = job(unique("stays-taken"), runs::incrementAndGet, Duration.ofHours(1), false);
        var a = new ClusterScheduler(nodeA, () -> NOON);
        var b = new ClusterScheduler(nodeB, () -> NOON.plusSeconds(2));

        a.fire(onA, NOON);
        b.fire(onA, NOON);

        assertThat(runs).hasValue(1);
        a.close();
        b.close();
    }

    @Test
    void aRunOnOneNodeKeepsTheNextFireOffEveryOtherNodeUntilItEnds() throws Exception {
        var released = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var otherRuns = new AtomicInteger();
        String name = unique("exclusive");
        var onA = job(name, () -> {
            started.countDown();
            try {
                released.await();
            } catch (InterruptedException ignored) {
                // Ends the run.
            }
        }, Duration.ofHours(1), false);
        var onB = job(name, otherRuns::incrementAndGet, Duration.ofHours(1), false);
        var first = NOON.plusSeconds(3600);
        var second = NOON.plusSeconds(7200);
        var third = NOON.plusSeconds(10800);
        var clock = new java.util.concurrent.atomic.AtomicReference<>(first);
        var a = new ClusterScheduler(nodeA, clock::get);
        var b = new ClusterScheduler(nodeB, clock::get);

        Thread run = inBackground(() -> a.fire(onA, first));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        clock.set(second);
        b.fire(onB, second);
        assertThat(otherRuns).hasValue(0);

        released.countDown();
        run.join(5000);
        clock.set(third);
        b.fire(onB, third);

        assertThat(otherRuns).hasValue(1);
        a.close();
        b.close();
    }

    @Test
    void aNodeThatStopsRenewingLetsRedisExpireItsRunClaimSoAnotherNodeCanStart() throws Exception {
        var stuck = new CountDownLatch(1);
        var releaseStuck = new CountDownLatch(1);
        String name = unique("dead-node");
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
        // Cut off after 100ms, so it stops renewing and its claim has one second of Redis's own clock left.
        var onA = job(name, ignoresInterrupts, Duration.ofMillis(100), false);
        var nextRuns = new AtomicInteger();
        var onB = job(name, nextRuns::incrementAndGet, Duration.ofHours(1), false);
        var first = NOON.plusSeconds(3600);
        var next = NOON.plusSeconds(7200);
        var a = new ClusterScheduler(nodeA, () -> first, Duration.ofSeconds(1));
        var b = new ClusterScheduler(nodeB, () -> next, Duration.ofSeconds(1));
        try {
            inBackground(() -> a.fire(onA, first));
            assertThat(stuck.await(5, TimeUnit.SECONDS)).isTrue();

            // Refused while the claim is live, and given once Redis has expired it.
            b.fire(onB, next);
            assertThat(nextRuns).hasValue(0);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (nextRuns.get() == 0 && System.nanoTime() < deadline) {
                b.fire(onB, next);
                Thread.sleep(100);
            }
            assertThat(nextRuns).hasValue(1);
        } finally {
            releaseStuck.countDown();
            a.close();
            b.close();
        }
    }

    @Test
    void twoNodesOnARealTimerRunEachFireOnceAgainstRedis() throws Exception {
        List<Instant> ranAt = new CopyOnWriteArrayList<>();
        String name = unique("every-second");
        Runnable record = () -> ranAt.add(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        var everySecond = CronExpression.parse("* * * * * *");
        var jobA = new ClusterScheduler.Job(name, everySecond, ZoneOffset.UTC, record, Duration.ofHours(1), false);
        var jobB = new ClusterScheduler.Job(name, everySecond, ZoneOffset.UTC, record, Duration.ofHours(1), false);
        var a = new ClusterScheduler(nodeA, InstantSource.system());
        var b = new ClusterScheduler(nodeB, InstantSource.system());
        a.add(jobA);
        b.add(jobB);
        try {
            a.start();
            b.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (ranAt.size() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            a.close();
            b.close();
        }

        assertThat(ranAt).hasSizeGreaterThanOrEqualTo(4);
        // Two nodes, one run per second: never two runs in the same second.
        assertThat(new HashSet<>(ranAt)).hasSameSizeAs(new ArrayList<>(ranAt));
    }

    /** What every context's job records, so two contexts' runs can be told apart from one's. */
    static final List<Instant> SPRING_RUNS = new CopyOnWriteArrayList<>();

    static class EverySecond {
        @HengeScheduled(cron = "* * * * * *", zone = "UTC", name = "redis-every-second")
        public void tick() {
            SPRING_RUNS.add(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.fixture.echo")
    static class Base {
        @Bean
        EverySecond everySecond() {
            return new EverySecond();
        }
    }

    private static AnnotationConfigApplicationContext processOnRedis() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("henge.store.type", "redis", "henge.store.redis.uri", uri)));
        ctx.register(Base.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void twoSpringProcessesOnRedisRunAJobOncePerFire() throws Exception {
        SPRING_RUNS.clear();
        try (var one = processOnRedis(); var two = processOnRedis()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (SPRING_RUNS.size() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        }

        assertThat(SPRING_RUNS).hasSizeGreaterThanOrEqualTo(4);
        assertThat(new HashSet<>(SPRING_RUNS)).hasSameSizeAs(new ArrayList<>(SPRING_RUNS));
    }
}
