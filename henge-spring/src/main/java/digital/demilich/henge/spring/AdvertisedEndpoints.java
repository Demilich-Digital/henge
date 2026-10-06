package digital.demilich.henge.spring;

import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.Epoch;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Where to call a service nobody configured a url for: whoever advertises it
 * ({@link HengeServiceAdvertiser}). Each service version's advertisers are kept in a routing table and re-read at most
 * once per refresh interval, by whichever call finds its entry stale, so the datastore sees a read per
 * service per interval however many calls are made. Calls rotate through what's advertised.
 *
 * <p>The routing table is this process's copy of the datastore's answer, not a cache in front of an
 * optional store: the datastore is on the critical path. An entry lives one refresh interval and is
 * replaced, or evicted, whenever the datastore is next reached; only while it can't be reached is a
 * stale one kept and served. The table is only trusted as far as the datastore is: if a read comes back empty <em>and</em> the
 * epoch has changed, the storage may just have been wiped and its hosts not yet had a heartbeat to
 * advertise again, so the previous answer is kept for one more interval. An empty read from the same
 * epoch is believed. A datastore that can't be read keeps serving what the table last read.
 */
class AdvertisedEndpoints {

    private static final Log log = LogFactory.getLog(AdvertisedEndpoints.class);

    private record Route(List<String> urls, Epoch epoch, Instant fetchedAt) {
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration refreshInterval;
    private final InstantSource clock;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final AtomicInteger rotation = new AtomicInteger();
    private final SystemMetrics metrics;

    /** @param metrics told how many nodes advertise a service version, each time that is read */
    AdvertisedEndpoints(SystemEphemeralDatastore datastore, Duration refreshInterval, InstantSource clock, SystemMetrics metrics) {
        this.datastore = datastore;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** The next base URL to call for {@code service@version}, or {@code null} if nobody advertises one. */
    String next(String service, int version) {
        List<String> urls = urls(service, version);
        return urls.isEmpty() ? null : urls.get(Math.floorMod(rotation.getAndIncrement(), urls.size()));
    }

    /**
     * {@code url} was just tried and wasn't there (unreachable, or it no longer serves the service):
     * stop offering it until the advertisements are next read, unless it's the only one left, which is
     * still the best guess there is.
     */
    void failed(String service, int version, String url) {
        routes.computeIfPresent(ServiceAdvertisement.key(service, version), (key, known) -> {
            List<String> remaining = known.urls().stream().filter(candidate -> !candidate.equals(url)).toList();
            return remaining.isEmpty() ? known : new Route(remaining, known.epoch(), known.fetchedAt());
        });
    }

    /**
     * The datastore has just been reached, so what it hasn't been asked about since a whole interval ago is
     * let go: an entry is only kept past its time while the datastore can't be reached to replace it.
     */
    private void evictStale(String justRead, Instant now) {
        routes.entrySet().removeIf(entry -> !entry.getKey().equals(justRead)
                && !now.isBefore(entry.getValue().fetchedAt().plus(refreshInterval)));
    }

    private List<String> urls(String service, int version) {
        String key = ServiceAdvertisement.key(service, version);
        Route known = routes.get(key);
        Instant now = clock.instant();
        if (known != null && now.isBefore(known.fetchedAt().plus(refreshInterval))) {
            return known.urls();
        }
        try {
            SystemEphemeralDatastore.Snapshot snapshot = datastore.read(key);
            List<String> fresh = snapshot.members().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(MemberId::nodeId).thenComparing(MemberId::localName)))
                    .map(member -> ServiceAdvertisement.decode(member.getValue()).url())
                    .filter(url -> url != null && !url.isBlank())
                    .distinct()
                    .toList();
            boolean maybeWiped = fresh.isEmpty() && known != null && !known.urls().isEmpty()
                    && !snapshot.epoch().equals(known.epoch());
            if (!maybeWiped) {
                // What was seen, only when it is believed: an empty read from a storage that may have just
                // been wiped says nothing about how many nodes are there.
                metrics.advertisersSeen(service, version, snapshot.members().size());
            }
            Route updated = new Route(maybeWiped ? known.urls() : fresh, snapshot.epoch(), now);
            routes.put(key, updated);
            evictStale(key, now);
            return updated.urls();
        } catch (RuntimeException e) {
            if (known == null) {
                throw e instanceof StoreUnavailableException ? e
                        : new StoreUnavailableException("Can't look up who hosts " + key + ": " + e.getMessage(), e);
            }
            GuardedDatastore.logFailure(log, "Couldn't refresh the advertisements for " + key + "; using the last ones read", e);
            // Not recorded as fetched: the next call tries again.
            return known.urls();
        }
    }
}
