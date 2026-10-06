package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * Runs {@code @HengeScheduled} jobs once per fire across the cluster. Every node computes the same
 * nominal fire instants from a job's cron expression, so they agree on what a fire is without talking
 * to each other, and the first to {@code claim} the key {@code cron:<job>@<instant>} runs it. The
 * claim is never released: it lapses by itself, so a node whose clock is a little behind finds the
 * fire already taken, however quickly the job finished.
 *
 * <p>A fire is only run if it is less than {@link #MAX_LATENESS} late, and its claim lives for
 * {@link #FIRE_TTL}, twice that: so a claim can't lapse while a node still believes the fire is
 * due. A late fire is skipped, never caught up, and so is one whose claim can't be made because
 * the store is away.
 *
 * <p>A claim is atomic within one copy of the key, so a store that is failing over can let two nodes
 * both win the same fire, as it can over-grant a lease. A job must be safe to run twice.
 */
final class ClusterScheduler implements AutoCloseable {

    /** How long a fire's claim is kept; the key expires by itself. */
    static final Duration FIRE_TTL = Duration.ofMinutes(10);

    /** How late a node may run a fire. Half {@link #FIRE_TTL}, so the claim outlives every node's chance to run it. */
    static final Duration MAX_LATENESS = FIRE_TTL.dividedBy(2);

    /** This node's member under a fire's key; the datastore tells nodes apart by their {@code nodeId}. */
    static final String MEMBER = "node";

    private static final Log log = LogFactory.getLog(ClusterScheduler.class);

    /** A job: a name that is the same on every node, when it is due, and what it does. */
    record Job(String name, CronExpression cron, ZoneId zone, Runnable body) {
    }

    private final SystemEphemeralDatastore datastore;
    private final InstantSource clock;
    private final List<Job> jobs = new ArrayList<>();
    private ScheduledExecutorService timer;
    private ExecutorService workers;

    ClusterScheduler(SystemEphemeralDatastore datastore, InstantSource clock) {
        this.datastore = datastore;
        this.clock = clock;
    }

    /** Before {@link #start}. */
    void add(Job job) {
        jobs.add(job);
    }

    /** Starts every job added, each from now. */
    synchronized void start() {
        if (timer != null || jobs.isEmpty()) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "henge-scheduler"));
        AtomicInteger workerNumber = new AtomicInteger();
        workers = Executors.newCachedThreadPool(runnable -> daemon(runnable, "henge-scheduled-" + workerNumber.incrementAndGet()));
        for (Job job : jobs) {
            schedule(job, clock.instant());
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Arranges for {@code job}'s first fire after {@code after}, and then each one after that. */
    private void schedule(Job job, Instant after) {
        ZonedDateTime next = job.cron().next(after.atZone(job.zone()));
        if (next == null) {
            log.info("Henge scheduled job '" + job.name() + "' has no further fires");
            return;
        }
        Instant nominal = next.toInstant();
        // In nanoseconds, not truncated to milliseconds: a fire never runs before its instant.
        long delayNanos = Math.max(0, Duration.between(clock.instant(), nominal).toNanos());
        try {
            timer.schedule(() -> {
                Instant now = clock.instant();
                try {
                    workers.execute(() -> fire(job, nominal));
                    // After the later of the two, so a node that was suspended doesn't walk through every fire it missed.
                    schedule(job, now.isAfter(nominal) ? now : nominal);
                } catch (RejectedExecutionException e) {
                    // Closed.
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            // Closed.
        }
    }

    /** Runs {@code job}'s fire at {@code nominal} if this node wins it. */
    void fire(Job job, Instant nominal) {
        Duration lateness = Duration.between(nominal, clock.instant());
        if (lateness.compareTo(MAX_LATENESS) > 0) {
            log.warn("Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal + ", " + lateness.toSeconds()
                    + "s late; a missed fire is never caught up");
            return;
        }
        boolean won;
        try {
            won = datastore.claim(key(job, nominal), MEMBER, 1, 1, FIRE_TTL);
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal
                    + ": the ephemeral store couldn't be asked who runs it", e);
            return;
        }
        if (!won) {
            log.debug("Henge scheduled job '" + job.name() + "' at " + nominal + " is run by another node");
            return;
        }
        try {
            job.body().run();
        } catch (Throwable t) {
            log.error("Henge scheduled job '" + job.name() + "' failed (fire at " + nominal + ")", t);
        }
    }

    static String key(Job job, Instant nominal) {
        return "cron:" + job.name() + "@" + nominal;
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            workers.shutdownNow();
        }
    }
}
