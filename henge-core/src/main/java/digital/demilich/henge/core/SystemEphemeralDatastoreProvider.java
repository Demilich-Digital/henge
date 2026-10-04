package digital.demilich.henge.core;

import java.util.function.UnaryOperator;

/**
 * Lets a datastore implementation be chosen by configuration: an adapter module registers one of
 * these with {@link java.util.ServiceLoader} ({@code META-INF/services/} this interface), and
 * {@code henge.store.type=<type>} selects it. The in-process store is built in and isn't one.
 */
public interface SystemEphemeralDatastoreProvider {

    /** The value of {@code henge.store.type} that selects this provider, e.g. {@code redis}. */
    String type();

    /**
     * Builds the datastore. Its own settings live under {@code henge.store.<type>.*}; {@code property}
     * reads a configuration property by its full key and returns {@code null} if it isn't set. A store
     * that is {@link AutoCloseable} is closed when the application shuts down.
     *
     * @throws IllegalStateException with a message naming the property to fix, if it can't be built
     */
    SystemEphemeralDatastore create(UnaryOperator<String> property);
}
