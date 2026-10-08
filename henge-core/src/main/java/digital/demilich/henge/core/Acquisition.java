package digital.demilich.henge.core;

import java.time.Duration;
import java.util.Objects;

/**
 * What a bucket said to {@link RateLimiter#tryAcquire()} or {@link SystemEphemeralDatastore#tryAcquire}:
 * whether the permits were taken, and if not, how long until one permit fits.
 *
 * <p>Until {@code retryAfter} has passed, nothing fits for anyone: the level only falls at the bucket's
 * fixed leak rate, and another node's take only raises it. So a caller that refuses locally for that
 * long refuses nothing the bucket would have admitted, and it is a fair {@code Retry-After} for a
 * {@code 429}. It is measured on the store's clock and starts when the reply arrives.
 *
 * <p>It is the wait for one permit, not for the amount asked: a refusal of several permits when one
 * would fit has a {@code retryAfter} of zero. A request larger than the bucket's capacity never fits,
 * however long it waits.
 *
 * @param granted whether the permits were taken
 * @param retryAfter zero when granted; otherwise how long until one permit fits, possibly zero
 */
public record Acquisition(boolean granted, Duration retryAfter) {

    /** The permits were taken. */
    public static final Acquisition GRANTED = new Acquisition(true, Duration.ZERO);

    public Acquisition {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative() || (granted && !retryAfter.isZero())) {
            throw new IllegalArgumentException("retryAfter must be zero when granted and never negative, got " + retryAfter);
        }
    }

    /** Refused, and one permit fits after {@code retryAfter}. */
    public static Acquisition refused(Duration retryAfter) {
        return new Acquisition(false, retryAfter);
    }

    /**
     * The wait until one permit fits a bucket at {@code level}, in the units of {@link RateLimit}'s
     * buckets (1/period of a permit, leaking {@code permits} a millisecond): the time the level takes to
     * fall to one permit below the capacity, rounded up to a whole millisecond.
     */
    static Duration untilOnePermitFits(long level, RateLimit limit) {
        long period = limit.periodMillis();
        long excess = level + period - limit.capacity() * period;
        return excess <= 0 ? Duration.ZERO : Duration.ofMillis(Math.ceilDiv(excess, (long) limit.permits()));
    }
}
