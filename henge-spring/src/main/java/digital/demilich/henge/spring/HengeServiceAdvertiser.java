package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.Nullable;

/**
 * Tells the cluster which services this process hosts: for every service version {@code /_henge}
 * will serve here (a leased one only if its lease was granted), it keeps this node's member of
 * {@code adv:<service>@<version>} on the datastore, renewed on a heartbeat of a third of the TTL. A
 * crashed process disappears one TTL later; a graceful stop withdraws first.
 *
 * <p>It runs as the last {@link SmartLifecycle} to start, so a process advertises only once the
 * context is fully built (the lease decisions are made, the web server is up), and the first to
 * stop, so it withdraws before the server stops answering. Callers read the advertisements to route
 * to a service with no configured url (see {@link AdvertisedEndpoints}).
 */
class HengeServiceAdvertiser implements SmartLifecycle, ApplicationEventPublisherAware {

    private static final Log log = LogFactory.getLog(HengeServiceAdvertiser.class);

    /** This node's member name under each advertisement key; a node hosts a service version once. */
    static final String MEMBER = "host";

    private final SystemEphemeralDatastore datastore;
    private final HengeServiceRegistry registry;
    private final ServiceAdvertisement advertisement;
    private final Duration ttl;
    private final SystemMetrics metrics;

    /** What was last advertised: what {@link #stop} takes back. */
    private volatile List<HengeServiceDescriptor> advertised = List.of();
    private final Set<String> withdrawn = new HashSet<>();
    private ScheduledExecutorService heartbeat;
    private boolean running;
    private volatile ApplicationEventPublisher events;

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    HengeServiceAdvertiser(SystemEphemeralDatastore datastore, HengeServiceRegistry registry, String advertiseUrl, Duration ttl,
            @Nullable SystemMetrics metrics) {
        this.metrics = metrics == null ? SystemMetrics.NONE : metrics;
        this.datastore = this.metrics.measured(datastore, "advertisement");
        if (datastore instanceof GuardedDatastore guard) {
            guard.onRecovery(this::renew);
        }
        this.registry = registry;
        this.advertisement = new ServiceAdvertisement(advertiseUrl);
        this.ttl = ttl;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        registry.onReady(this::renew);
        renew();
        if (registry.declaresAny()) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "henge-advertiser");
                thread.setDaemon(true);
                return thread;
            });
            long periodMillis = Math.max(1, ttl.toMillis() / 3);
            heartbeat.scheduleWithFixedDelay(BackgroundTasks.surviving("advertisement refresh", this::renew, log, () -> events),
                    periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Writes (or renews) the advertisement of every service version hosted here and not withdrawn, as
     * hosted now: a leased one that was waiting for the datastore joins once it has been decided. Nothing
     * is advertised until the process is ready ({@link HengeBootGate}). Also the heartbeat. Synchronized
     * so a withdrawal is never undone by a renewal in flight.
     */
    synchronized void renew() {
        if (!running || !registry.isReady()) {
            return;
        }
        advertised = registry.hosted().stream()
                .filter(service -> !withdrawn.contains(service.name() + "@" + service.version()))
                .toList();
        byte[] value = advertisement.encode();
        for (HengeServiceDescriptor service : advertised) {
            try {
                datastore.put(ServiceAdvertisement.key(service.name(), service.version()), MEMBER, value, ttl);
                metrics.advertisementRenewed(service.name(), service.version(), true);
            } catch (RuntimeException e) {
                metrics.advertisementRenewed(service.name(), service.version(), false);
                GuardedDatastore.logFailure(log, "Advertising " + service.name() + "@" + service.version() + " failed; will retry on the next heartbeat", e);
            }
        }
    }

    /**
     * Stops advertising one service version, now and on every later heartbeat; the others carry on. Taking the
     * advertisement out of the store is best-effort: it is no longer renewed, so one that stays lapses within its
     * TTL, and a caller that still routes here meanwhile gets a {@code 404}, which means nothing ran, and is
     * retried on the next host.
     */
    synchronized void withdraw(String service, int version) {
        withdrawn.add(service + "@" + version);
        advertised = advertised.stream()
                .filter(hosted -> !(hosted.name().equals(service) && hosted.version() == version))
                .toList();
        remove(service, version);
    }

    private void remove(String service, int version) {
        try {
            datastore.remove(ServiceAdvertisement.key(service, version), MEMBER);
        } catch (RuntimeException e) {
            GuardedDatastore.logFailure(log, "Withdrawing " + service + "@" + version + " failed; it lapses within " + ttl, e);
        }
    }

    /** Undoes {@link #withdraw}: the service version is advertised again from the next {@link #renew}, once it is hosted here. */
    synchronized void resume(String service, int version) {
        withdrawn.remove(service + "@" + version);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
        for (HengeServiceDescriptor service : advertised) {
            remove(service.name(), service.version());
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
