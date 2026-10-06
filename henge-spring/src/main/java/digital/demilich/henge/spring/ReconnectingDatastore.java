package digital.demilich.henge.spring;

import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * A datastore that couldn't be connected to when the process started, and is connected to when it
 * first can be: each use tries again until the connection is made, failing with
 * {@link StoreUnavailableException} meanwhile, which is what lets the process start, not ready, rather
 * than not start (see {@link HengeBootGate}). The {@link GuardedDatastore} around it spaces the attempts out.
 */
final class ReconnectingDatastore implements SystemEphemeralDatastore, AutoCloseable {

    private final Supplier<SystemEphemeralDatastore> connect;
    private volatile SystemEphemeralDatastore connected;

    /** @param connect makes the connection, throwing {@link StoreUnavailableException} while it can't */
    ReconnectingDatastore(Supplier<SystemEphemeralDatastore> connect) {
        this.connect = connect;
    }

    private SystemEphemeralDatastore datastore() {
        SystemEphemeralDatastore datastore = connected;
        if (datastore == null) {
            synchronized (this) {
                datastore = connected;
                if (datastore == null) {
                    datastore = connect.get();
                    connected = datastore;
                }
            }
        }
        return datastore;
    }

    /** @throws StoreUnavailableException until the connection is made: the id is the connected store's */
    @Override
    public String nodeId() {
        return datastore().nodeId();
    }

    @Override
    public void put(String key, String localName, byte[] value, Duration ttl) {
        datastore().put(key, localName, value, ttl);
    }

    @Override
    public void remove(String key, String localName) {
        datastore().remove(key, localName);
    }

    @Override
    public Snapshot read(String key) {
        return datastore().read(key);
    }

    @Override
    public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        return datastore().claim(key, localName, amount, capacity, ttl);
    }

    @Override
    public boolean tryAcquire(String key, int amount, RateLimit limit) {
        return datastore().tryAcquire(key, amount, limit);
    }

    @Override
    public void close() throws Exception {
        if (connected instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
