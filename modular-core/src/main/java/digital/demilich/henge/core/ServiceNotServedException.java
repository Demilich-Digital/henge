package digital.demilich.henge.core;

/**
 * A process was asked for a service version it doesn't serve: it never hosted it, has since withdrawn
 * it, or wasn't granted the lease it needs. The call was <em>not run</em>, so it is safe to make again
 * elsewhere whatever the method does, which is what {@code internal-rest} does when the process was
 * found by its advertisement. Thrown by the dispatcher and rebuilt on the caller like any other
 * exception, so an application can catch it.
 *
 * <p>{@code 404} over {@code internal-rest}, logged at debug by the serving process: it's the caller's
 * (stale) information, not an error there.
 */
@ErrorStatus(404)
@ErrorLogLevel(ErrorLogLevel.Level.DEBUG)
public class ServiceNotServedException extends RuntimeException {

    public ServiceNotServedException(String message) {
        super(message);
    }
}
