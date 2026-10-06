package digital.demilich.henge.core;

/**
 * Henge couldn't reach the ephemeral store, and had nothing it had read earlier to fall back on: who
 * hosts a service, whether a lease or a rate-limit permit is available.
 *
 * <p>The store is on the critical path, so this is a real failure, not a degraded answer. It is
 * {@code 503 Service Unavailable} over {@code internal-rest}, and a servlet application running
 * {@code henge-spring-boot-starter} answers it with a {@code 503} at its own edge unless the
 * application handles the exception itself. Henge never retries it: only a call that provably never
 * ran is retried.
 *
 * <p>A node that had already read what it needs keeps serving it while the store is away; this is
 * thrown only when there is nothing to serve.
 */
@ErrorStatus(503)
@ErrorLogLevel(ErrorLogLevel.Level.WARN)
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message) {
        super(message);
    }

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
