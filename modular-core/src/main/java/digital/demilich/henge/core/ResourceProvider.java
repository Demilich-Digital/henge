package digital.demilich.henge.core;

/**
 * Builds the resource behind a lease, once per node. A lease with a provider (a class annotated
 * {@link LeasedResource}) has its resource opened when this node is granted the lease, handed to every
 * service constructor that asks for it with {@link RequiresLease}, and closed when the last of them lets
 * go, which is also when the lease is handed back. So every service and version on the node shares one
 * resource, sized from the one {@link Lease#amount()} the node claimed.
 *
 * <p>Henge builds the provider through Spring's dependency injection, like a service implementation,
 * so it can take whatever configuration it needs in its constructor.
 *
 * @param <T> the type of the resource; a constructor parameter that asks for it must accept this type
 */
public interface ResourceProvider<T> {

    /** Builds the resource, sized from {@code lease}. */
    T open(Lease lease) throws Exception;

    /** Releases the resource. By default closes it if it is {@link AutoCloseable}, as a connection pool is. */
    default void close(T resource) throws Exception {
        if (resource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
