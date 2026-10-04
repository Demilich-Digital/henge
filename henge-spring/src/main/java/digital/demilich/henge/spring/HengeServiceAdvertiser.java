package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
 * stop, so it withdraws before the server stops answering. Nothing reads the advertisements yet.
 */
class HengeServiceAdvertiser implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(HengeServiceAdvertiser.class);

    /** This node's member name under each advertisement key; a node hosts a service version once. */
    static final String MEMBER = "host";

    private final SystemEphemeralDatastore datastore;
    private final HengeServiceRegistry registry;
    private final ServiceAdvertisement advertisement;
    private final Duration ttl;
    private final SystemMetrics metrics;

    private List<HengeServiceDescriptor> advertised = List.of();
    private ScheduledExecutorService heartbeat;
    private boolean running;

    /** @param metrics null (there are none to report to) is as good as {@link SystemMetrics#NONE} */
    HengeServiceAdvertiser(SystemEphemeralDatastore datastore, HengeServiceRegistry registry, String advertiseUrl, Duration ttl,
            @Nullable SystemMetrics metrics) {
        this.metrics = metrics == null ? SystemMetrics.NONE : metrics;
        this.datastore = this.metrics.measured(datastore, "advertisement");
        this.registry = registry;
        this.advertisement = new ServiceAdvertisement(advertiseUrl);
        this.ttl = ttl;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        advertised = registry.hosted();
        renew();
        if (!advertised.isEmpty()) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "henge-advertiser");
                thread.setDaemon(true);
                return thread;
            });
            long periodMillis = Math.max(1, ttl.toMillis() / 3);
            heartbeat.scheduleWithFixedDelay(this::renew, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        }
        running = true;
    }

    /** Writes (or renews) every advertisement; also the heartbeat. */
    void renew() {
        byte[] value = advertisement.encode();
        for (HengeServiceDescriptor service : advertised) {
            try {
                datastore.put(ServiceAdvertisement.key(service.name(), service.version()), MEMBER, value, ttl);
                metrics.advertisementRenewed(service.name(), service.version(), true);
            } catch (RuntimeException e) {
                metrics.advertisementRenewed(service.name(), service.version(), false);
                log.warn("Advertising " + service.name() + "@" + service.version() + " failed; will retry on the next heartbeat", e);
            }
        }
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
            datastore.remove(ServiceAdvertisement.key(service.name(), service.version()), MEMBER);
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
