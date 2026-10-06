package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * Runs {@code @HengeScheduled} jobs once per fire across the cluster. Two claims on the datastore, one
 * for each thing a cluster has to agree on:
 *
 * <p><b>The fire.</b> Every node computes the same nominal fire instants from a job's cron expression,
 * so they agree on what a fire is without talking to each other, and the first to {@code claim} the key
 * {@code cron:<job>@<instant>} runs it. The claim is never released: it lapses by itself, so a node
 * whose clock is a little behind finds the fire already taken, however quickly the job finished. A fire is
 * only run if it is less than {@link #MAX_LATENESS} late, and its claim lives for {@link #FIRE_TTL}, twice
 * that: so a claim can't lapse while a node still believes the fire is due. A late fire is skipped, never
 * caught up, and so is one whose claim can't be made because the store is away.
 *
 * <p><b>The run.</b> The node that won the fire then claims {@code cron:<job>:running} for as long as the
 * method executes, renewed on a heartbeat of a third of {@link #runTtl}, and released when it ends. A node
 * that dies stops renewing and gives the claim back one TTL later. If the claim is refused the fire is
 * skipped, on every node alike: it is not queued. A job that allows overlap claims
 * {@code cron:<job>:running@<instant>} instead, which no other fire holds, so a fire is never in the way
 * of the last run, but a second winner of the same fire is. A run is
 * also cut off after the job's {@code maxRuntime}: its thread is interrupted and renewing stops, so a hung
 * run, which would otherwise keep reporting itself alive, can't block the job forever. A renewal the store
 * refuses means another node was given the job, and interrupts the run; one that can't be made because the
 * store is away is retried, since nothing else can have been given the job without the store either.
 *
 * <p>A claim is atomic within one copy of the key, so a store that is failing over can let two nodes
 * both win a fire or a run, as it can over-grant a lease. A job must be safe to run twice.
 */
final class ClusterScheduler implements AutoCloseable {

    /** How long a fire's claim is kept; the key expires by itself. */
    static final Duration FIRE_TTL = Duration.ofMinutes(10);

    /** How late a node may run a fire. Half {@link #FIRE_TTL}, so the claim outlives every node's chance to run it. */
    static final Duration MAX_LATENESS = FIRE_TTL.dividedBy(2);

    /** How long a run's claim lasts without a renewal. */
    static final Duration DEFAULT_RUN_TTL = Duration.ofSeconds(30);

    /** This node's member under a fire's key; the datastore tells nodes apart by their {@code nodeId}. */
    static final String MEMBER = "node";

    private static final Log log = LogFactory.getLog(ClusterScheduler.class);

    /**
     * A job: a name that is the same on every node, when it is due, what it does, how long a run may take,
     * and whether a fire may start while the last run is still going.
     */
    record Job(String name, CronExpression cron, ZoneId zone, Runnable body, Duration maxRuntime, boolean overlap) {
    }

    private final SystemEphemeralDatastore datastore;
    private final InstantSource clock;
    private final Duration runTtl;
    private final List<Job> jobs = new ArrayList<>();
    /** Wakes jobs when a fire is due; does nothing slow, so that a slow store can't make a fire late. */
    private ScheduledExecutorService timer;
    private ExecutorService workers;
    /** Renews the claims of runs, and cuts them off; apart from the {@link #timer} because renewing waits on the store. */
    private final ScheduledExecutorService watchdog;

    ClusterScheduler(SystemEphemeralDatastore datastore, InstantSource clock) {
        this(datastore, clock, DEFAULT_RUN_TTL);
    }

    ClusterScheduler(SystemEphemeralDatastore datastore, InstantSource clock, Duration runTtl) {
        this.datastore = datastore;
        this.clock = clock;
        this.runTtl = runTtl;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "henge-scheduled-watchdog"));
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

    /** Runs {@code job}'s fire at {@code nominal} if this node wins it, and, unless the job overlaps, its run. */
    void fire(Job job, Instant nominal) {
        Duration lateness = Duration.between(nominal, clock.instant());
        if (lateness.compareTo(MAX_LATENESS) > 0) {
            log.warn("Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal + ", " + lateness.toSeconds()
                    + "s late; a missed fire is never caught up");
            return;
        }
        boolean won;
        try {
            won = datastore.claim(fireKey(job, nominal), MEMBER, 1, 1, FIRE_TTL);
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal
                    + ": the ephemeral store couldn't be asked who runs it", e);
            return;
        }
        if (!won) {
            log.debug("Henge scheduled job '" + job.name() + "' at " + nominal + " is run by another node");
            return;
        }
        // A name of its own for each run, so that a second run on this node isn't mistaken for a renewal of the first.
        String runName = "run-" + UUID.randomUUID();
        if (!claimRun(job, nominal, runName)) {
            return;
        }
        run(job, nominal, runName);
    }

    /** Takes the job's run claim; false, having said why, if this fire must be skipped. */
    private boolean claimRun(Job job, Instant nominal, String runName) {
        String key = runKey(job, nominal);
        boolean granted;
        try {
            granted = datastore.claim(key, runName, 1, 1, runTtl);
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal
                    + ": the ephemeral store couldn't be asked whether the last run is over", e);
            return false;
        }
        if (!granted && job.overlap()) {
            // Nothing else is running under this key but this fire's own run: two nodes won the fire.
            log.warn("Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal + ": another node is already "
                    + "running it" + runningOn(key) + ", so two nodes won the same fire (the store may have been failing over)");
        } else if (!granted) {
            log.info("Henge scheduled job '" + job.name() + "' skipped its fire at " + nominal + ": the last run is still going"
                    + runningOn(key) + ". Skipped, not queued.");
        }
        return granted;
    }

    /** Where the job is running, as far as the store says, to explain a skip. */
    private String runningOn(String runKey) {
        try {
            List<String> nodes = datastore.read(runKey).members().keySet().stream().map(MemberId::nodeId).sorted().toList();
            return nodes.isEmpty() ? "" : " on node " + String.join(", ", nodes);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** One run of a job, on the thread executing it: whether it is still going, so that nothing interrupts a thread that has moved on. */
    private static final class Run {
        private final Thread worker = Thread.currentThread();
        private final List<ScheduledFuture<?>> watching = new CopyOnWriteArrayList<>();
        private boolean done;

        synchronized void interrupt() {
            if (!done) {
                worker.interrupt();
            }
        }

        /** The run is over: stops watching it, and clears an interrupt that came too late for it to matter. */
        synchronized void finish() {
            done = true;
            watching.forEach(future -> future.cancel(false));
            Thread.interrupted();
        }

        void stopRenewing() {
            watching.forEach(future -> future.cancel(false));
        }
    }

    private void run(Job job, Instant nominal, String runName) {
        Run run = new Run();
        String key = runKey(job, nominal);
        long periodMillis = Math.max(1, runTtl.toMillis() / 3);
        run.watching.add(watchdog.scheduleWithFixedDelay(() -> renew(job, key, runName, run), periodMillis, periodMillis,
                TimeUnit.MILLISECONDS));
        run.watching.add(watchdog.schedule(() -> {
            log.warn("Henge scheduled job '" + job.name() + "' (fire at " + nominal + ") ran past its maxRuntime of "
                    + job.maxRuntime() + "; interrupting it. The next fire may start while it is still running if it "
                    + "ignores that.");
            run.stopRenewing();
            run.interrupt();
        }, job.maxRuntime().toNanos(), TimeUnit.NANOSECONDS));
        try {
            job.body().run();
        } catch (Throwable t) {
            log.error("Henge scheduled job '" + job.name() + "' failed (fire at " + nominal + ")", t);
        } finally {
            run.finish();
            try {
                datastore.remove(key, runName);
            } catch (RuntimeException e) {
                // The claim lapses with its TTL; until it does, the next fire of a job that doesn't overlap is skipped.
                GuardedDatastore.logFailure(log, "Handing back the run of '" + job.name() + "' failed", e);
            }
        }
    }

    private void renew(Job job, String key, String runName, Run run) {
        try {
            if (!datastore.claim(key, runName, 1, 1, runTtl)) {
                log.warn("Henge scheduled job '" + job.name() + "' lost its run claim to another node; interrupting this run");
                run.interrupt();
                throw new CancelRenewal();
            }
        } catch (CancelRenewal e) {
            throw e;
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Renewing the run of '" + job.name() + "' failed", e);
        }
    }

    /** Thrown out of the heartbeat to stop it: a scheduled task that throws is not run again. */
    private static final class CancelRenewal extends RuntimeException {
        CancelRenewal() {
            super(null, null, false, false);
        }
    }

    static String fireKey(Job job, Instant nominal) {
        return "cron:" + job.name() + "@" + nominal;
    }

    /**
     * The claim a run holds. A job that doesn't overlap has one for all its runs, so a fire finds the last
     * run in the way; one that does has one per fire, which no other fire shares, so only a second winner of
     * the same fire is in the way. Either way it is what shows that the run is going, and where.
     */
    static String runKey(Job job, Instant nominal) {
        return job.overlap() ? "cron:" + job.name() + ":running@" + nominal : "cron:" + job.name() + ":running";
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            workers.shutdownNow();
        }
        watchdog.shutdownNow();
    }
}
