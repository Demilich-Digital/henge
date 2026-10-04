package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Acquires, holds and releases this process's leases. A lease is a claim on the datastore key
 * {@code lease:<name>}, one member per service version hosted here ({@code service@version}),
 * granted all-or-nothing per service. Held claims are renewed on a heartbeat of a third of the TTL, so a
 * crashed process gives its capacity back one TTL later; once granted a lease is kept for the
 * life of the process (there is no eviction yet).
 *
 * <p>It also remembers which services this process was granted, because that decides what
 * {@code /_modular} may serve: a service whose lease was refused here is reached remotely, and
 * must not answer here. See {@link ModularServiceRegistry}.
 */
class ModularLeaseKeeper implements DisposableBean {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final Log log = LogFactory.getLog(ModularLeaseKeeper.class);

    private record Claim(String key, String localName, LeaseNeed need) {
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration ttl;
    private final Map<String, List<Claim>> heldByService = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    ModularLeaseKeeper(SystemEphemeralDatastore datastore, Duration ttl) {
        this.datastore = datastore;
        this.ttl = ttl;
    }

    /** Claims every lease in {@code needs} for {@code localName}; {@code null} if granted, else the lease that was refused (nothing is kept). */
    LeaseNeed acquireAll(String localName, List<LeaseNeed> needs) {
        List<LeaseNeed> ordered = needs.stream().sorted(Comparator.comparing(LeaseNeed::name)).toList();
        List<Claim> acquired = new ArrayList<>();
        for (LeaseNeed need : ordered) {
            Claim claim = new Claim(key(need.name()), localName, need);
            if (!datastore.claim(claim.key(), localName, need.amount(), need.capacity(), ttl)) {
                acquired.forEach(this::release);
                return need;
            }
            acquired.add(claim);
        }
        heldByService.put(localName, List.copyOf(acquired));
        startHeartbeat();
        return null;
    }

    /** Whether {@code localName} ({@code service@version}) was granted its leases by this process. */
    boolean hosts(String localName) {
        return heldByService.containsKey(localName);
    }

    void release(String localName) {
        List<Claim> claims = heldByService.remove(localName);
        if (claims != null) {
            claims.forEach(this::release);
        }
    }

    private void release(Claim claim) {
        datastore.remove(claim.key(), claim.localName());
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
        for (List<Claim> claims : heldByService.values()) {
            for (Claim claim : claims) {
                try {
                    if (!datastore.claim(claim.key(), claim.localName(), claim.need().amount(), claim.need().capacity(), ttl)) {
                        log.warn("Lease '" + claim.need().name() + "' could not be renewed for " + claim.localName()
                                + ": the cluster now holds more of it than its capacity of " + claim.need().capacity()
                                + ". The service keeps running here, since nothing evicts yet.");
                    }
                } catch (RuntimeException e) {
                    log.warn("Renewing lease '" + claim.need().name() + "' for " + claim.localName() + " failed", e);
                }
            }
        }
    }

    @Override
    public synchronized void destroy() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        Set.copyOf(heldByService.keySet()).forEach(this::release);
    }

    static String key(String lease) {
        return "lease:" + lease;
    }
}
