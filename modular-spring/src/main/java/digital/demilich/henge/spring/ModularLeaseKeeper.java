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
 * <p>It also remembers which services this process was granted, because that decides what
 * {@code /_modular} may serve: a service whose lease was refused here is reached remotely, and
 * must not answer here. See {@link ModularServiceRegistry}.
 */
class ModularLeaseKeeper implements DisposableBean, BeanFactoryAware {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** This node's member under each lease key; a node claims a lease once, whoever on it uses it. */
    static final String MEMBER = "node";

    private static final Log log = LogFactory.getLog(ModularLeaseKeeper.class);

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

        Held(LeaseNeed need) {
            this.need = need;
        }
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration ttl;
    private final Map<String, Held> heldByLease = new LinkedHashMap<>();
    private final Map<String, List<String>> leasesByService = new LinkedHashMap<>();
    private ScheduledExecutorService heartbeat;
    private BeanFactory beanFactory;

    ModularLeaseKeeper(SystemEphemeralDatastore datastore, Duration ttl) {
        this.datastore = datastore;
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
            if (heldByLease.containsKey(need.name())) {
                continue;
            }
            if (!datastore.claim(key(need.name()), MEMBER, need.amount(), need.capacity(), ttl)) {
                claimedHere.forEach(claimed -> drop(claimed.name()));
                return need;
            }
            heldByLease.put(need.name(), new Held(need));
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

    /** Whether {@code localName} ({@code service@version}) was granted its leases by this process. */
    synchronized boolean hosts(String localName) {
        return leasesByService.containsKey(localName);
    }

    /** {@code localName} lets go of its leases; each one nobody else here holds is handed back. */
    synchronized void release(String localName) {
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

    private void drop(String lease) {
        Held held = heldByLease.remove(lease);
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
        List<LeaseNeed> held;
        synchronized (this) {
            held = heldByLease.values().stream().map(h -> h.need).toList();
        }
        for (LeaseNeed need : held) {
            try {
                if (!datastore.claim(key(need.name()), MEMBER, need.amount(), need.capacity(), ttl)) {
                    log.warn("Lease '" + need.name() + "' could not be renewed: the cluster now holds more of it than its "
                            + "capacity of " + need.capacity() + ". Its services keep running here, since nothing evicts yet.");
                }
            } catch (RuntimeException e) {
                log.warn("Renewing lease '" + need.name() + "' failed", e);
            }
        }
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
