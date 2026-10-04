package digital.demilich.henge.spring;

import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import org.springframework.lang.Nullable;

/**
 * Builds the {@link RateLimiter} behind {@code henge.rate-limits.<name>}: {@link HengeServiceRegistrar}
 * registers one bean per configured name, made by {@link #create}, and qualified
 * {@code @RateLimited(name)}. Its bucket is {@code rate:<name>} in the datastore, and a subject's
 * {@code rate:<name>:<subject>}.
 */
final class RateLimiters {

    private RateLimiters() {
    }

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    static RateLimiter create(String name, RateLimit limit, SystemEphemeralDatastore datastore, @Nullable SystemMetrics metrics) {
        SystemMetrics reportTo = metrics == null ? SystemMetrics.NONE : metrics;
        return new RateLimiter(new Reporting(reportTo.measured(datastore, "rate-limit"), name, reportTo), "rate:" + name, limit);
    }

    /** Counts what the limiter is told, granted or refused, under its name; a subject is never a tag. */
    private record Reporting(SystemEphemeralDatastore delegate, String name, SystemMetrics metrics)
            implements SystemEphemeralDatastore {

        @Override
        public boolean tryAcquire(String key, int amount, RateLimit limit) {
            boolean granted = delegate.tryAcquire(key, amount, limit);
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
