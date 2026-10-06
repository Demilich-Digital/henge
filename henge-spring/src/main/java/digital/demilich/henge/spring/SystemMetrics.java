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

    /**
     * {@code datastore}, timing each operation as made for {@code purpose} ({@code lease}, {@code advertisement},
     * {@code routing}, {@code rate-limit}).
     */
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

    /** The rate limiter {@code limit} granted or refused permits, for its whole bucket or a subject's. */
    default void rateLimitAcquired(String limit, boolean granted) {
    }

    /** {@code nodes} nodes draw on rate limiter {@code limit}, as of this node's last heartbeat: what a node's share is a fraction of while the datastore is away. */
    default void rateLimitSubscribers(String limit, int nodes) {
    }

    /** A call to rate limiter {@code limit} was decided from this node's own share, because the datastore couldn't be reached. */
    default void rateLimitDegraded(String limit) {
    }

    /**
     * A channel to {@code service@version} exists on this node: {@code side} is {@code frontend} (it holds the
     * client's end, and the connection to the backend) or {@code backend} (it hosts the service). Each is matched
     * by one {@link #channelClosed}.
     */
    default void channelOpened(String service, int version, String side) {
    }

    /** The channel reported by {@link #channelOpened} closed with the websocket close code {@code status}. */
    default void channelClosed(String service, int version, String side, int status) {
    }

    /** This node's trunk count, on {@code side}, went up: a frontend connected to a backend, or one connected to this node. */
    default void trunkOpened(String side) {
    }

    default void trunkClosed(String side) {
    }
}
