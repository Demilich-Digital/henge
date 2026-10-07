package digital.demilich.henge.spring;

import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.Epoch;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/**
 * Takes up a lease after startup. A service whose lease was refused is reached remotely, and stays
 * a candidate here: every so often the poller looks at whether the lease has room now (a holder
 * shut down, or crashed and lapsed) and, if it does, claims it and hosts the service.
 *
 * <p>Several nodes refused at once will all want the same capacity, and a node that claims some of a
 * service's leases and is then refused another holds the first for a moment before handing it back,
 * which can turn away a node that would have fitted. So a candidate is only attempted when a
 * {@code read} of every lease it needs shows room for it, and the read is followed by the claim with
 * nothing in between: the window in which a read can be stale is a few store round trips, set against
 * attempts spread over tens of seconds. Each candidate has its own schedule, from {@code interval},
 * jittered by &plusmn;50% so that nodes refused together (at the same deploy) don't poll together,
 * and doubling with each refusal up to {@code maxInterval}, so a cluster that really is full is
 * asked less and less often. A failed attempt isn't retried sooner; a lost race is just another refusal.
 *
 * <p>A store that was wiped looks empty to everyone, and the holders of a lease only say otherwise on their
 * next renewal. A poller that took the room it saw then would take a holder's place, and push the cluster
 * over the capacity. So when a lease's {@linkplain SystemEphemeralDatastore.Epoch epoch} changes between two
 * looks, it waits one lease TTL, enough for every holder to have renewed, before it trusts what it reads.
 *
 * <p>Nothing is polled until the process is ready ({@link HengeBootGate}), and nothing at all while
 * there is no candidate: the common case, where every lease was granted, costs no store operation.
 * The read is advisory, and the claim stays the decision.
 */
class HengeLeasePoller implements SmartLifecycle {

    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_MAX_INTERVAL = Duration.ofMinutes(5);

    private static final Log log = LogFactory.getLog(HengeLeasePoller.class);

    /** A service refused its leases here, and what hosts it if they are granted: true once it is. */
    record Candidate(String localName, List<LeaseNeed> needs, BooleanSupplier host) {
    }

    private static final class Entry {
        final Candidate candidate;
        int refusals;
        Instant nextAt;

        Entry(Candidate candidate, Instant nextAt) {
            this.candidate = candidate;
            this.nextAt = nextAt;
        }
    }

    private final SystemEphemeralDatastore datastore;
    private final HengeLeaseKeeper keeper;
    private final Duration interval;
    private final Duration maxInterval;
    private final Duration settle;
    private final ObjectProvider<HengeBootGate> gate;
    private final List<Entry> entries = new ArrayList<>();
    /** The epoch each lease's key last answered from, and until when a change in it is being waited out. */
    private final Map<String, Epoch> epochs = new HashMap<>();
    private final Map<String, Instant> settlingUntil = new HashMap<>();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduled;
    private boolean running;

    /** @param settle how long to wait after the store may have been wiped: the leases' TTL */
    HengeLeasePoller(SystemEphemeralDatastore datastore, HengeLeaseKeeper keeper, Duration interval, Duration maxInterval,
            Duration settle, ObjectProvider<HengeBootGate> gate) {
        this.datastore = datastore;
        this.keeper = keeper;
        this.interval = interval;
        this.maxInterval = maxInterval.compareTo(interval) < 0 ? interval : maxInterval;
        this.settle = settle;
        this.gate = gate;
    }

    /** Starts polling for {@code candidate}, first after one jittered interval. */
    synchronized void add(Candidate candidate) {
        entries.add(new Entry(candidate, Instant.now().plus(jittered(interval))));
        ensureScheduled();
    }

    /** How many services are waiting to be hosted here. */
    synchronized int candidates() {
        return entries.size();
    }

    private void ensureScheduled() {
        if (!running || scheduled != null || entries.isEmpty()) {
            return;
        }
        // Waking up is a local check of the clock; only a due candidate costs the store anything.
        long periodMillis = Math.max(1, interval.toMillis() / 10);
        scheduled = executor.scheduleWithFixedDelay(() -> tick(Instant.now()), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Attempts every candidate that is due at {@code now}. Also the schedule's own beat. */
    void tick(Instant now) {
        HengeBootGate bootGate = gate.getIfAvailable();
        if (bootGate != null && !bootGate.isReady()) {
            return;
        }
        List<Entry> due;
        synchronized (this) {
            due = entries.stream().filter(entry -> !entry.nextAt.isAfter(now)).toList();
            if (entries.isEmpty() && scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
        }
        for (Entry entry : due) {
            Outcome outcome;
            try {
                outcome = attempt(entry.candidate, now);
            } catch (StoreUnavailableException e) {
                // The store is away: not a refusal, so no backoff is earned; the next beat asks again, and
                // the guard around the store spaces the attempts.
                GuardedDatastore.logFailure(log, "Polling for the lease of " + entry.candidate.localName() + " failed", e);
                return;
            } catch (RuntimeException e) {
                log.warn("Hosting " + entry.candidate.localName() + " after its lease was granted failed; it is reached remotely", e);
                outcome = Outcome.FULL;
            }
            synchronized (this) {
                if (outcome == Outcome.HOSTED) {
                    entries.remove(entry);
                } else if (outcome == Outcome.SETTLING) {
                    // Not a refusal: the cluster isn't known to be full, so no backoff is earned.
                    entry.nextAt = now.plus(jittered(interval));
                } else {
                    entry.refusals++;
                    entry.nextAt = now.plus(jittered(backoff(entry.refusals)));
                }
            }
        }
    }

    private enum Outcome {
        /** Every lease showed room, was claimed, and the service is hosted here now. */
        HOSTED,
        /** What a look at one lease finds when it fits: not an outcome of an attempt, which goes on to claim. */
        ROOM,
        /** A lease the candidate needs is full. */
        FULL,
        /** The store may have been wiped lately, so what it says about a lease can't be trusted yet. */
        SETTLING
    }

    private Outcome attempt(Candidate candidate, Instant now) {
        for (LeaseNeed need : candidate.needs()) {
            if (keeper.isHeld(need.name())) {
                continue;
            }
            // Read and claim back to back: the less time between them, the less a read is out of date.
            Outcome room = room(need, now);
            if (room != Outcome.ROOM) {
                return room;
            }
        }
        return candidate.host().getAsBoolean() ? Outcome.HOSTED : Outcome.FULL;
    }

    /** Whether {@code need} fits in its lease, as of one read: {@link Outcome#ROOM}, {@link Outcome#FULL} or {@link Outcome#SETTLING}. */
    private Outcome room(LeaseNeed need, Instant now) {
        SystemEphemeralDatastore.Snapshot snapshot = datastore.read(HengeLeaseKeeper.key(need.name()));
        Epoch previous = epochs.put(need.name(), snapshot.epoch());
        if (previous != null && !previous.equals(snapshot.epoch())) {
            log.info("The store holding lease '" + need.name() + "' was restarted or wiped: waiting " + settle
                    + " for its holders to say so before taking up room in it");
            settlingUntil.put(need.name(), now.plus(settle));
        }
        if (settlingUntil.getOrDefault(need.name(), Instant.MIN).isAfter(now)) {
            return Outcome.SETTLING;
        }
        MemberId own = new MemberId(datastore.nodeId(), HengeLeaseKeeper.MEMBER);
        long others = snapshot.members().entrySet().stream()
                .filter(member -> !member.getKey().equals(own))
                // Including what is being given up: the resource is in use until it has been closed.
                .mapToLong(member -> Math.abs((long) SystemEphemeralDatastore.claimedAmount(member.getValue())))
                .sum();
        return others + need.amount() <= need.capacity() ? Outcome.ROOM : Outcome.FULL;
    }

    /** The wait after {@code refusals} refusals in a row: doubling from the interval to its maximum. */
    private Duration backoff(int refusals) {
        Duration wait = interval;
        for (int i = 1; i < refusals && wait.compareTo(maxInterval) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxInterval) > 0 ? maxInterval : wait;
    }

    /** {@code base}, give or take half of it, at random. */
    static Duration jittered(Duration base) {
        return Duration.ofMillis((long) (base.toMillis() * (0.5 + ThreadLocalRandom.current().nextDouble())));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "henge-lease-poller");
            thread.setDaemon(true);
            return thread;
        });
        ensureScheduled();
    }

    @Override
    public void stop() {
        ScheduledExecutorService stopping;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            scheduled = null;
            stopping = executor;
        }
        // Not shutdownNow: an attempt in flight is hosting a service, and is let finish before the context goes.
        stopping.shutdown();
        try {
            stopping.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    /** With the advertiser: first to stop, so nothing is hosted as the context closes. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
