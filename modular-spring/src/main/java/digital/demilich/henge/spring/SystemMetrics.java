package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;

/**
 * What the system itself does, for the meters nobody can put on it from outside: leases claimed and
 * renewed, advertisements kept up, calls retried and given up on, and the datastore all of that stands on.
 * Everything that reports takes this and not a Micrometer type, so it loads without Micrometer on the
 * classpath; {@link MicrometerSystemMetrics} is the one that counts, and {@link #NONE} does nothing.
 *
 * <p>Every dimension here is bounded by the code and the configuration (a lease, a service version, a
 * fixed set of outcomes), never by the traffic.
 */
interface SystemMetrics {

    /** Reports nothing. */
    SystemMetrics NONE = new SystemMetrics() {
    };

    /** How a held lease's renewal went. */
    enum Renewal {
        RENEWED, OVER_CAPACITY, ERROR
    }

    /** {@code datastore}, timing each operation as made for {@code purpose} ({@code lease}, {@code advertisement}, {@code routing}). */
    default SystemEphemeralDatastore measured(SystemEphemeralDatastore datastore, String purpose) {
        return datastore;
    }

    /**
     * This node asked the cluster for {@code lease}, on behalf of a service that needs it and doesn't find it held
     * already. A refusal holds nothing, so each service that asks after one asks (and is counted) again.
     */
    default void leaseClaimed(String lease, boolean granted) {
    }

    /** A lease this node holds was renewed on the heartbeat. */
    default void leaseRenewed(String lease, Renewal outcome) {
    }

    /** This node holds {@code amount} of {@code lease}; zero once it hands it back. */
    default void leaseHeld(String lease, int amount) {
    }

    /** This node renewed its advertisement of {@code service@version}, or couldn't. */
    default void advertisementRenewed(String service, int version, boolean succeeded) {
    }

    /**
     * A caller looking for {@code service@version} read the advertisements and found {@code nodes} nodes
     * advertising it. Only what a lookup happens to see: it is as old as the last call to that service.
     */
    default void advertisersSeen(String service, int version, int nodes) {
    }

    /** A call to {@code service@version} that never ran is being tried again; {@code reason} is {@code connect} or {@code not-served}. */
    default void callRetried(String service, int version, String reason) {
    }

    /** A call to {@code service@version} that never ran was given up on, retries used up or not allowed. */
    default void callGaveUp(String service, int version, String reason) {
    }

    /** An advertised host failed a call to {@code service@version}, and isn't offered again for a while. */
    default void endpointFailed(String service, int version) {
    }
}
