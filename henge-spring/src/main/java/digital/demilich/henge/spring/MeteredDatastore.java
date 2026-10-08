package digital.demilich.henge.spring;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A {@link SystemEphemeralDatastore} that times every operation on the one it wraps, as
 * {@value #NAME}. The datastore is where every cluster-wide fact lives, so its latency and errors are
 * what a lease that can't be renewed, or an advertisement that lapses, come down to.
 *
 * <p>Tags: {@code purpose} (who asked: the leases, the advertisements, the routing of calls, or a rate limiter),
 * {@code operation} ({@code put}, {@code remove}, {@code read}, {@code count}, {@code sample}, {@code claim},
 * {@code tryAcquire}) and
 * {@code outcome} ({@code success} or {@code error}). A {@code claim} or {@code tryAcquire} the
 * cluster refuses is a success: the store did its job.
 */
final class MeteredDatastore implements SystemEphemeralDatastore {

    static final String NAME = "henge.store.operations";

    private final SystemEphemeralDatastore delegate;
    private final MeterRegistry registry;
    private final String purpose;

    MeteredDatastore(SystemEphemeralDatastore delegate, MeterRegistry registry, String purpose) {
        this.delegate = delegate;
        this.registry = registry;
        this.purpose = purpose;
    }

    @Override
    public String nodeId() {
        return delegate.nodeId();
    }

    @Override
    public void put(String key, String localName, byte[] value, Duration ttl) {
        timed("put", () -> {
            delegate.put(key, localName, value, ttl);
            return null;
        });
    }

    @Override
    public void remove(String key, String localName) {
        timed("remove", () -> {
            delegate.remove(key, localName);
            return null;
        });
    }

    @Override
    public Snapshot read(String key) {
        return timed("read", () -> delegate.read(key));
    }

    @Override
    public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        return timed("claim", () -> delegate.claim(key, localName, amount, capacity, ttl));
    }

    @Override
    public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
        return timed("tryAcquire", () -> delegate.tryAcquire(key, amount, limit));
    }

    @Override
    public Count count(String key) {
        return timed("count", () -> delegate.count(key));
    }

    @Override
    public Sample sample(String key, int limit) {
        return timed("sample", () -> delegate.sample(key, limit));
    }

    private <T> T timed(String operation, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            Timer.builder(NAME)
                    .tags("purpose", purpose, "operation", operation, "outcome", outcome)
                    .register(registry)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
