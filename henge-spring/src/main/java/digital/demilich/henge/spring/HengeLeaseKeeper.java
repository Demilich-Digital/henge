package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.ResourceProvider;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.lang.Nullable;

/**
 * Acquires, holds and releases this process's leases. A lease is a claim on the datastore key
 * {@code lease:<name>}, written once per node as the member {@link #MEMBER} however many of this
 * node's services declare it: they share the claim, and the resource it stands for. A service's
 * leases are granted all-or-nothing. Held claims are renewed on a heartbeat of a third of the TTL, so
 * a crashed process gives its capacity back one TTL later; once granted a lease is kept while any
 * service here still holds it, and handed back when the last one lets go (a service that failed to
 * start, or the context closing).
 *
 * <p>A lease with a provider ({@code @LeasedResource}) has its resource opened when the claim is first
 * granted, shared by every service that holds it, and closed when the claim is handed back.
 *
 * <p>A renewal the store refuses means the claims in the store, this node's own excluded, leave no room
 * for it under the capacity this node is configured with: its own claim was lost (it lapsed, or the store
 * was wiped and another node claimed the capacity first), or another node believes in a different
 * capacity, as nodes do during a rollout that changes it. Either way the store is never made to hold more
 * than a claim's capacity at the time it is made, and this node must not carry on: it stops renewing, so every service
 * standing on the lease stops being hosted here and runs its eviction ({@link #onEviction}), which hands
 * the lease back and makes room for the nodes that are not refused.
 *
 * <p>A service whose lease was refused here is reached remotely, and must not answer here: the
 * caller of {@link #acquireAll} makes that its {@link ServiceBinding}'s target, and
 * {@link HengeServiceRegistry} follows the binding.
 */
class HengeLeaseKeeper implements DisposableBean, BeanFactoryAware {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** This node's member under each lease key; a node claims a lease once, whoever on it uses it. */
    static final String MEMBER = "node";

    private static final Log log = LogFactory.getLog(HengeLeaseKeeper.class);

    /** The bean name of the provider of {@code lease}'s resource, when it has one. */
    static String providerBeanName(String lease) {
        return "henge.lease-provider." + lease;
    }

    /**
     * One lease this node has claimed, the services (by {@code service@version}) standing on it, and
     * its resource if it has a provider.
     */
    private static final class Held {
        final LeaseNeed need;
        final Set<String> holders = new LinkedHashSet<>();
        ResourceProvider<Object> provider;
        Object resource;
        /** Being given up: the claim is written as one until it is handed back, and no service may join it. */
        boolean leaving;

        Held(LeaseNeed need) {
            this.need = need;
        }
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration ttl;
    private final SystemMetrics metrics;
    private final Map<String, Held> heldByLease = new LinkedHashMap<>();
    private final Map<String, List<String>> leasesByService = new LinkedHashMap<>();
    /** What gives a service up when its lease is lost, by {@code service@version}. */
    private final Map<String, Runnable> evictors = new LinkedHashMap<>();
    private ScheduledExecutorService heartbeat;
    private BeanFactory beanFactory;

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    HengeLeaseKeeper(SystemEphemeralDatastore datastore, Duration ttl, @Nullable SystemMetrics metrics) {
        this.metrics = metrics == null ? SystemMetrics.NONE : metrics;
        this.datastore = this.metrics.measured(datastore, "lease");
        this.ttl = ttl;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    /**
     * Gives {@code localName} every lease in {@code needs}; {@code null} if granted, else the lease that
     * was refused. A lease this node already holds is shared, not claimed again. Nothing is kept on a
     * refusal: whatever this call claimed is handed back, and the service holds nothing.
     */
    synchronized LeaseNeed acquireAll(String localName, List<LeaseNeed> needs) {
        List<LeaseNeed> ordered = needs.stream().sorted(Comparator.comparing(LeaseNeed::name)).toList();
        List<LeaseNeed> claimedHere = new ArrayList<>();
        for (LeaseNeed need : ordered) {
            Held held = heldByLease.get(need.name());
            if (held != null && !held.leaving) {
                continue;
            }
            if (held != null) {
                // Being given up: nothing joins it, and it can't be claimed again until it has been handed back.
                claimedHere.forEach(claimed -> drop(claimed.name()));
                return need;
            }
            boolean granted;
            try {
                granted = datastore.claim(key(need.name()), MEMBER, need.amount(), need.capacity(), ttl);
            } catch (RuntimeException e) {
                // Asked again later, from nothing: what this call claimed is let go, as on a refusal.
                claimedHere.forEach(claimed -> dropQuietly(claimed.name()));
                throw e;
            }
            metrics.leaseClaimed(need.name(), granted);
            if (!granted) {
                claimedHere.forEach(claimed -> drop(claimed.name()));
                return need;
            }
            heldByLease.put(need.name(), new Held(need));
            metrics.leaseHeld(need.name(), need.amount());
            claimedHere.add(need);
        }
        // Only once everything is granted, so a refusal never builds a resource just to close it.
        try {
            for (LeaseNeed need : claimedHere) {
                open(heldByLease.get(need.name()));
            }
        } catch (RuntimeException e) {
            claimedHere.forEach(claimed -> drop(claimed.name()));
            throw e;
        }
        for (LeaseNeed need : ordered) {
            heldByLease.get(need.name()).holders.add(localName);
        }
        leasesByService.put(localName, ordered.stream().map(LeaseNeed::name).toList());
        startHeartbeat();
        return null;
    }

    /**
     * What to run, off the heartbeat, when {@code localName}'s lease can't be kept: it must stop hosting
     * the service and {@link #release} it, which hands the lease back once its last holder here has.
     */
    synchronized void onEviction(String localName, Runnable evictor) {
        evictors.put(localName, evictor);
    }

    /** Whether this node already holds {@code lease}, for some service of its own: a claim it needn't ask the cluster for. */
    synchronized boolean isHeld(String lease) {
        Held held = heldByLease.get(lease);
        return held != null && !held.leaving;
    }

    /** The resource of {@code lease}, which this node must hold and which must have a provider. */
    synchronized Object resource(String lease) {
        Held held = heldByLease.get(lease);
        if (held == null || held.resource == null) {
            throw new IllegalStateException("Lease '" + lease + "' has no resource here");
        }
        return held.resource;
    }

    @SuppressWarnings("unchecked")
    private void open(Held held) {
        String lease = held.need.name();
        String beanName = providerBeanName(lease);
        if (beanFactory == null || !beanFactory.containsBean(beanName)) {
            return;
        }
        ResourceProvider<Object> provider = beanFactory.getBean(beanName, ResourceProvider.class);
        Object resource;
        try {
            resource = provider.open(new Lease(lease, held.need.amount()));
        } catch (Exception e) {
            throw new IllegalStateException("The provider of lease '" + lease + "' failed to open its resource", e);
        }
        if (resource == null) {
            throw new IllegalStateException("The provider of lease '" + lease + "' returned null instead of a resource");
        }
        held.provider = provider;
        held.resource = resource;
    }

    /** {@code localName} lets go of its leases; each one nobody else here holds is handed back. */
    synchronized void release(String localName) {
        evictors.remove(localName);
        List<String> leases = leasesByService.remove(localName);
        if (leases == null) {
            return;
        }
        for (String lease : leases) {
            Held held = heldByLease.get(lease);
            held.holders.remove(localName);
            if (held.holders.isEmpty()) {
                drop(lease);
            }
        }
    }

    private void dropQuietly(String lease) {
        try {
            drop(lease);
        } catch (RuntimeException e) {
            // The claim lapses with its TTL; nothing else to do for it.
            GuardedDatastore.logFailure(log, "Handing back lease '" + lease + "' failed", e);
        }
    }

    private void drop(String lease) {
        Held held = heldByLease.remove(lease);
        if (held != null) {
            metrics.leaseHeld(lease, 0);
        }
        if (held != null && held.resource != null) {
            try {
                held.provider.close(held.resource);
            } catch (Exception e) {
                log.warn("Closing the resource of lease '" + lease + "' failed", e);
            }
        }
        datastore.remove(key(lease), MEMBER);
    }

    private synchronized void startHeartbeat() {
        if (heartbeat == null) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "henge-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            long periodMillis = Math.max(1, ttl.toMillis() / 3);
            heartbeat.scheduleWithFixedDelay(this::renewAll, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    void renewAll() {
        record Renewal(LeaseNeed need, boolean leaving) {
        }
        List<Renewal> held;
        synchronized (this) {
            held = heldByLease.values().stream().map(h -> new Renewal(h.need, h.leaving)).toList();
        }
        for (Renewal renewal : held) {
            LeaseNeed need = renewal.need();
            try {
                if (renewal.leaving()) {
                    // Being given up: the claim is kept as one until it is handed back, and written again if the store lost it.
                    datastore.claim(key(need.name()), MEMBER, -need.amount(), need.capacity(), ttl);
                } else if (datastore.claim(key(need.name()), MEMBER, need.amount(), need.capacity(), ttl)) {
                    metrics.leaseRenewed(need.name(), SystemMetrics.Renewal.RENEWED);
                } else {
                    metrics.leaseRenewed(need.name(), SystemMetrics.Renewal.LOST);
                    lost(need);
                }
            } catch (RuntimeException e) {
                metrics.leaseRenewed(need.name(), SystemMetrics.Renewal.ERROR);
                GuardedDatastore.logFailure(log, "Renewing lease '" + need.name() + "' failed", e);
            }
        }
    }

    /** The renewal of {@code need} was refused: this node stops renewing, and everything standing on it stops being hosted here. */
    private void lost(LeaseNeed need) {
        List<Runnable> toRun = new ArrayList<>();
        synchronized (this) {
            Held held = heldByLease.get(need.name());
            if (held == null || held.leaving) {
                return;
            }
            held.leaving = true;
            held.holders.forEach(holder -> {
                Runnable evictor = evictors.get(holder);
                if (evictor != null) {
                    toRun.add(evictor);
                }
            });
        }
        log.warn("Lease '" + need.name() + "' can't be kept: the rest of the cluster holds more of it than leaves room for this "
                + "node's " + need.amount() + " within the capacity of " + need.capacity() + " it is configured with (its claim was lost, "
                + "or other nodes are configured with another capacity). " + toRun.size() + " service(s) stop being hosted here.");
        try {
            // Said at once, not at the next heartbeat: the store has turned an existing claim into this itself, but a
            // claim that was gone has to be written, and the nodes staying must not be asked to make room meanwhile.
            datastore.claim(key(need.name()), MEMBER, -need.amount(), need.capacity(), ttl);
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Marking lease '" + need.name() + "' as given up failed; the heartbeat will", e);
        }
        // Each takes a grace period and a drain, so none runs on the heartbeat, which has other leases to renew.
        toRun.forEach(evictor -> Thread.ofVirtual().name("henge-lease-eviction").start(evictor));
    }

    @Override
    public synchronized void destroy() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        Set.copyOf(leasesByService.keySet()).forEach(this::release);
    }

    static String key(String lease) {
        return "lease:" + lease;
    }
}
