package digital.demilich.henge.core;

/**
 * A grant of {@code amount} units of the cluster-wide resource {@code name}, handed to a service
 * implementation that declared {@link RequiresLease} for it. Henge only does the bookkeeping: the
 * service is trusted to size the real resource (a connection pool, say) from {@link #amount()}.
 */
public record Lease(String name, int amount) {
}
