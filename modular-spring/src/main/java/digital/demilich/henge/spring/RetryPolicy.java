package digital.demilich.henge.spring;

import java.time.Duration;

/**
 * When {@code internal-rest} tries a call again. Only for failures that mean the call <em>never ran</em>
 * on the remote, so repeating it is safe for every method, whatever it does: the connection couldn't
 * be made, or a {@code 404} came back. A {@code 404} is defined to mean nothing happened (see
 * {@link digital.demilich.henge.core.ErrorStatus}): the process doesn't serve the service (it never did,
 * withdrew it, wasn't granted its lease, or isn't a Henge process at all, like a proxy in front of
 * one), or the implementation threw an {@code @ErrorStatus(404)} exception, which it must only do
 * before it has had any effect. A call that may have started (a read timeout, a 5xx, any other
 * exception thrown by the implementation) is never retried; whether that is safe is a question about
 * the method, not the transport.
 *
 * <p>A retry goes to the next advertised host where there is one, and otherwise to the same url, which
 * is what a load balancer or a Kubernetes Service in front of several processes wants.
 *
 * @param maxAttempts calls made at most, the first included; 1 turns retries off
 * @param backoff the wait before each retry
 */
record RetryPolicy(int maxAttempts, Duration backoff, boolean onConnectFailure, boolean onNotServed) {

    static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(50), true, true);
}
