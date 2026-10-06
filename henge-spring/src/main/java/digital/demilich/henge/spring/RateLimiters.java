package digital.demilich.henge.spring;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.time.InstantSource;
import org.springframework.lang.Nullable;

/**
 * Builds the {@link RateLimiter} behind {@code henge.rate-limits.<name>}: {@link HengeServiceRegistrar}
 * registers one bean per configured name, made by {@link #create}, and qualified
 * {@code @RateLimited(name)}. Its bucket is {@code rate:<name>} in the datastore, and a subject's
 * {@code rate:<name>:<subject>}.
 *
 * <p>When the datastore can't be reached the limiter degrades rather than failing: it draws on a bucket
 * of its own in this process, sized to this node's share of the limit, {@code 1/N} for the {@code N}
 * nodes {@link RateLimitSubscriptions} last counted, so that the cluster as a whole stays within the
 * limit it was given. It returns to the shared bucket as soon as the datastore answers.
 */
final class RateLimiters {

    private RateLimiters() {
    }

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    static RateLimiter create(String name, RateLimit limit, SystemEphemeralDatastore datastore,
            RateLimitSubscriptions subscriptions, @Nullable SystemMetrics metrics) {
        return createWithClock(name, limit, datastore, subscriptions, metrics, InstantSource.system());
    }

    /** As {@link #create}, with the clock the degraded bucket leaks by. */
    static RateLimiter createWithClock(String name, RateLimit limit, SystemEphemeralDatastore datastore,
            RateLimitSubscriptions subscriptions, @Nullable SystemMetrics metrics, InstantSource clock) {
        SystemMetrics reportTo = metrics == null ? SystemMetrics.NONE : metrics;
        Reporting store = new Reporting(reportTo.measured(datastore, "rate-limit"), name, reportTo,
                subscriptions.subscribe(name), new InProcessEphemeralDatastore(clock));
        return new RateLimiter(store, key(name), limit);
    }

    /** The key of limiter {@code name}: its bucket, and its subscribers as members. */
    static String key(String name) {
        return "rate:" + name;
    }

    /**
     * {@code limit}'s share for one of {@code nodes}: a {@code 1/nodes} burst, and the sustained rate
     * spread over a period {@code nodes} times as long, which is exact. Past the longest period a limit
     * can have, the permits are divided instead, rounded down; that is exact too unless there are fewer
     * of them than nodes, when each node still gets one and the cluster can exceed the limit.
     */
    static RateLimit share(RateLimit limit, int nodes) {
        if (nodes <= 1) {
            return limit;
        }
        int capacity = Math.max(1, limit.capacity() / nodes);
        long period = limit.periodMillis() * nodes;
        if (period <= RateLimit.LONGEST_PERIOD.toMillis()) {
            return new RateLimit(capacity, limit.permits(), Duration.ofMillis(period));
        }
        return new RateLimit(capacity, Math.max(1, limit.permits() / nodes), limit.period());
    }

    /**
     * Counts what the limiter is told, granted or refused, under its name; a subject is never a tag. A
     * store that can't be reached is answered from {@code local}, in this node's share.
     */
    private record Reporting(SystemEphemeralDatastore delegate, String name, SystemMetrics metrics,
            RateLimitSubscriptions.Subscription subscription, SystemEphemeralDatastore local)
            implements SystemEphemeralDatastore {

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            boolean granted;
            try {
                granted = delegate.tryAcquire(key, amount, limit);
            } catch (StoreUnavailableException e) {
                if (subscription.nodes() == 0) {
                    throw e; // never reached the store, so no share to take: refuse
                }
                metrics.rateLimitDegraded(name);
                granted = local.tryAcquire(key, amount, share(limit, subscription.nodes()));
            }
            metrics.rateLimitAcquired(name, granted);
            return granted;
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return delegate.claim(key, localName, amount, capacity, ttl);
        }
    }
}
