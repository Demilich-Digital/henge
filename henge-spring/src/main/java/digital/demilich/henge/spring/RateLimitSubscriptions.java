package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.Epoch;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.lang.Nullable;

/**
 * How many nodes draw on each rate limiter, so that a node cut off from the datastore can take its
 * share of the limit instead of all of it. Every node that builds a limiter registers a member under
 * the limiter's key ({@code rate:<name>}, a keyspace separate from the bucket's), renews it on a
 * heartbeat, and reads the members back: that count is {@code N}, as of the last heartbeat.
 *
 * <p>A node registers when the limiter is built, whether or not it ever calls it. That only makes each
 * node's share smaller, never the cluster's total larger. Registering is done at once and fails if the
 * datastore can't be reached, so a node that has never read {@code N} doesn't start serving.
 */
class RateLimitSubscriptions implements DisposableBean {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** This node's member under each limiter's key. */
    static final String MEMBER = "node";

    private static final Log log = LogFactory.getLog(RateLimitSubscriptions.class);

    /** The nodes drawing on one limiter, as last read. */
    static final class Subscription {
        private final String name;
        private volatile int nodes;
        private volatile Epoch epoch;

        private Subscription(String name) {
            this.name = name;
        }

        /** At least 1: this node. */
        int nodes() {
            return nodes;
        }
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration ttl;
    private final SystemMetrics metrics;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    RateLimitSubscriptions(SystemEphemeralDatastore datastore, Duration ttl, @Nullable SystemMetrics metrics) {
        this.metrics = metrics == null ? SystemMetrics.NONE : metrics;
        this.datastore = this.metrics.measured(datastore, "rate-limit");
        this.ttl = ttl;
    }

    /** Registers this node as drawing on {@code name}, and reads who else is. Throws if the datastore can't be reached. */
    Subscription subscribe(String name) {
        Subscription subscription = new Subscription(name);
        renew(subscription);
        subscriptions.put(name, subscription);
        startHeartbeat();
        return subscription;
    }

    private void renew(Subscription subscription) {
        String key = RateLimiters.key(subscription.name);
        datastore.put(key, MEMBER, new byte[0], ttl);
        SystemEphemeralDatastore.Snapshot snapshot = datastore.read(key);
        int read = Math.max(1, snapshot.members().size());
        // A storage that was just wiped hasn't had every node's heartbeat yet, so a smaller count from a
        // new epoch isn't believed until the next heartbeat confirms it. Too large a count only makes
        // shares smaller.
        boolean maybeWiped = read < subscription.nodes && !snapshot.epoch().equals(subscription.epoch);
        if (!maybeWiped) {
            subscription.nodes = read;
        }
        subscription.epoch = snapshot.epoch();
        metrics.rateLimitSubscribers(subscription.name, subscription.nodes);
    }

    private synchronized void startHeartbeat() {
        if (heartbeat == null) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "henge-rate-limit-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            long periodMillis = Math.max(1, ttl.toMillis() / 3);
            heartbeat.scheduleWithFixedDelay(this::renewAll, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    void renewAll() {
        for (Subscription subscription : subscriptions.values()) {
            try {
                renew(subscription);
            } catch (RuntimeException e) {
                GuardedDatastore.logFailure(log, "Registering with rate limiter '" + subscription.name
                        + "' failed; keeping the last count of " + subscription.nodes, e);
            }
        }
    }

    @Override
    public synchronized void destroy() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        for (Subscription subscription : subscriptions.values()) {
            try {
                datastore.remove(RateLimiters.key(subscription.name), MEMBER);
            } catch (RuntimeException e) {
                GuardedDatastore.logFailure(log, "Leaving rate limiter '" + subscription.name + "' failed", e);
            }
        }
        subscriptions.clear();
    }
}
