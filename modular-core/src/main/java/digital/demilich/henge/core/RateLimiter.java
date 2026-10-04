package digital.demilich.henge.core;

import java.util.Objects;

/**
 * A cluster-wide leaky-bucket rate limiter: every node that builds one on the same {@code key}, with
 * the same {@link RateLimit}, draws on one bucket in the {@link SystemEphemeralDatastore}. It
 * costs one store operation per call and holds no state of its own.
 */
public final class RateLimiter {

    private final SystemEphemeralDatastore store;
    private final String key;
    private final RateLimit limit;

    public RateLimiter(SystemEphemeralDatastore store, String key, RateLimit limit) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    /** Takes one permit if the bucket has room; false if the call should be refused. */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /** Takes {@code permits} at once, or none: a request larger than the bucket's capacity is always refused. */
    public boolean tryAcquire(int permits) {
        return store.tryAcquire(key, permits, limit);
    }
}
