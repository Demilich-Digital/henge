package digital.demilich.henge.spring;

import java.time.Duration;

/**
 * When {@code internal-rest} tries a call again. Only for failures that mean the call <em>never ran</em>
 * on the remote, so repeating it is safe for every method, whatever it does: the connection couldn't
 * be made, or the process answered that it doesn't serve the service (a host that advertised it and
 * has since withdrawn it, or a lease it wasn't granted). A call that may have started (a read
 * timeout, a 5xx, an exception thrown by the implementation) is never retried; whether that is safe
 * is a question about the method, not the transport.
 *
 * <p>A retry goes to the next advertised host where there is one, and otherwise to the same url.
 * A {@code not-served} reply is only retried against an advertised host, since the same configured
 * url would only say it again.
 *
 * @param maxAttempts calls made at most, the first included; 1 turns retries off
 * @param backoff the wait before each retry
 */
record RetryPolicy(int maxAttempts, Duration backoff, boolean onConnectFailure, boolean onNotServed) {

    static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(50), true, true);
}
