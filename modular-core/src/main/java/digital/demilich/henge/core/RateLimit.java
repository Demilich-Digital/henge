package digital.demilich.henge.core;

import java.time.Duration;
import java.util.Objects;

/**
 * The constants of a leaky bucket: it holds at most {@code capacity} permits, and drains
 * {@code permits} of them every {@code period}. Callers define these locally, in code or config,
 * and pass them with every {@link SystemEphemeralDatastore#tryAcquire}: the store keeps only the
 * bucket's level, so every caller of one key has to use the same limit.
 *
 * <p>{@code capacity} is the burst a quiet bucket absorbs; {@code permits} per {@code period} is
 * the sustained rate. The period is a whole number of milliseconds, between 1 ms and an hour, which
 * keeps the arithmetic exact in 53 bits on every store.
 */
public record RateLimit(int capacity, int permits, Duration period) {

    private static final Duration LONGEST_PERIOD = Duration.ofHours(1);

    public RateLimit {
        Objects.requireNonNull(period, "period");
        if (capacity < 1 || permits < 1) {
            throw new IllegalArgumentException("capacity and permits must be at least 1, got " + capacity + " and " + permits);
        }
        if (period.toMillis() < 1 || period.compareTo(LONGEST_PERIOD) > 0) {
            throw new IllegalArgumentException("period must be between 1ms and 1h, got " + period);
        }
    }

    /** A sustained {@code permitsPerSecond}, with room to burst to {@code capacity}. */
    public static RateLimit perSecond(int permitsPerSecond, int capacity) {
        return new RateLimit(capacity, permitsPerSecond, Duration.ofSeconds(1));
    }

    public long periodMillis() {
        return period.toMillis();
    }
}
