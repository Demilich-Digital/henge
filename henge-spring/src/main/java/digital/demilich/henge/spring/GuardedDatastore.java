package digital.demilich.henge.spring;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * A {@link SystemEphemeralDatastore} that, once the one it wraps fails, stops asking it for a while:
 * every use of the datastore in the process (the leases' heartbeat, the advertisements, the routing of
 * calls, the rate limiters) shares this one view of whether it is reachable, instead of each waiting
 * out its own connect timeout on every call.
 *
 * <p>After a failure, calls fail at once with {@link StoreUnavailableException} until the backoff
 * has passed, which doubles with each further failure up to a cap. Then one call is let through to
 * find out; the rest keep failing fast until it answers. A success ends the outage. An outage is logged
 * once when it starts and once when it ends, which is why callers log what this throws at debug.
 *
 * <p>An {@link IllegalArgumentException} is the caller's mistake, not the store's, and passes through
 * without counting.
 */
final class GuardedDatastore implements SystemEphemeralDatastore {

    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);

    private static final Log log = LogFactory.getLog(GuardedDatastore.class);

    private final SystemEphemeralDatastore delegate;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final InstantSource clock;

    private int failures;
    private Instant outageStart;
    private Instant nextAttempt = Instant.MIN;
    private RuntimeException lastFailure;
    private Instant lastOutageEnded;
    private final List<Runnable> recoveryListeners = new CopyOnWriteArrayList<>();

    GuardedDatastore(SystemEphemeralDatastore delegate, Duration initialBackoff, Duration maxBackoff, InstantSource clock) {
        this.delegate = delegate;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff.compareTo(initialBackoff) < 0 ? initialBackoff : maxBackoff;
        this.clock = clock;
    }

    SystemEphemeralDatastore delegate() {
        return delegate;
    }

    @Override
    public String nodeId() {
        return delegate.nodeId();
    }

    @Override
    public void put(String key, String localName, byte[] value, Duration ttl) {
        guarded(() -> {
            delegate.put(key, localName, value, ttl);
            return null;
        });
    }

    @Override
    public void remove(String key, String localName) {
        guarded(() -> {
            delegate.remove(key, localName);
            return null;
        });
    }

    @Override
    public Snapshot read(String key) {
        return guarded(() -> delegate.read(key));
    }

    @Override
    public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        return guarded(() -> delegate.claim(key, localName, amount, capacity, ttl));
    }

    @Override
    public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
        return guarded(() -> delegate.tryAcquire(key, amount, limit));
    }

    @Override
    public Count count(String key) {
        return guarded(() -> delegate.count(key));
    }

    @Override
    public Sample sample(String key, int limit) {
        return guarded(() -> delegate.sample(key, limit));
    }

    /**
     * When the last outage ended, or null if there hasn't been one. Something that reads the store after an
     * outage asks this after its read, since the read may itself be the call that ended it. An outage longer
     * than an entry's TTL lets every entry lapse with no change of epoch, so this is the only sign of it.
     */
    synchronized Instant lastOutageEnded() {
        return lastOutageEnded;
    }

    /**
     * Calls {@code listener} each time an outage ends, so a holder says what it holds again as soon as the store is
     * back, not at its next heartbeat. It runs on a thread of its own, outside this guard's lock, since it calls
     * the store, and the call that ended the outage may be a request's. A listener that throws is logged and the
     * rest still run.
     */
    void onRecovery(Runnable listener) {
        recoveryListeners.add(listener);
    }

    /** Logs a failed use of the datastore: at debug if this guard has already said so, else a warning. */
    static void logFailure(Log log, String message, RuntimeException failure) {
        if (failure instanceof StoreUnavailableException) {
            log.debug(message, failure);
        } else {
            log.warn(message, failure);
        }
    }

    private <T> T guarded(Supplier<T> call) {
        Instant now = clock.instant();
        synchronized (this) {
            if (failures > 0) {
                if (now.isBefore(nextAttempt)) {
                    throw new StoreUnavailableException("The ephemeral store is unreachable; trying again in "
                            + Duration.between(now, nextAttempt).toMillis() + " ms", lastFailure);
                }
                // This call is the probe: the others keep failing fast until it has answered.
                nextAttempt = now.plus(backoff());
            }
        }
        T result;
        try {
            result = call.get();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (StoreUnavailableException e) {
            failed(e);
            throw e;
        } catch (RuntimeException e) {
            failed(e);
            throw new StoreUnavailableException("The ephemeral store is unreachable: " + e.getMessage(), e);
        }
        succeeded();
        return result;
    }

    private synchronized void failed(RuntimeException cause) {
        Instant now = clock.instant();
        if (failures == 0) {
            outageStart = now;
            log.warn("The ephemeral store is unreachable; keeping what was last read and failing fast, retrying with backoff", cause);
        }
        failures++;
        lastFailure = cause;
        nextAttempt = now.plus(backoff());
    }

    private void succeeded() {
        if (endOutage() && !recoveryListeners.isEmpty()) {
            Thread.ofVirtual().name("henge-store-recovery").start(() -> {
                for (Runnable listener : recoveryListeners) {
                    try {
                        listener.run();
                    } catch (RuntimeException e) {
                        log.warn("Re-asserting after the ephemeral store's recovery failed", e);
                    }
                }
            });
        }
    }

    /** Whether this call ended an outage. */
    private synchronized boolean endOutage() {
        if (failures > 0) {
            log.info("The ephemeral store is reachable again, after " + failures + " failed attempts over "
                    + Duration.between(outageStart, clock.instant()).toSeconds() + " s");
            failures = 0;
            outageStart = null;
            lastOutageEnded = clock.instant();
            lastFailure = null;
            nextAttempt = Instant.MIN;
            return true;
        }
        return false;
    }

    /** The wait after the failures so far: the initial backoff, doubled for each further one, up to the cap. */
    private Duration backoff() {
        Duration wait = initialBackoff;
        for (int i = 1; i < failures && wait.compareTo(maxBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}
