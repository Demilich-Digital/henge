package digital.demilich.henge.spring;

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
 * ({@link ModularServiceAdvertiser}). Each service version's advertisers are cached and re-read at most
 * once per refresh interval, by whichever call finds the cache stale, so the datastore sees a read per
 * service per interval however many calls are made. Calls rotate through what's advertised.
 *
 * <p>The cache is only trusted as far as the datastore is: if a read comes back empty <em>and</em> the
 * epoch has changed, the storage may just have been wiped and its hosts not yet had a heartbeat to
 * advertise again, so the previous answer is kept for one more interval. An empty read from the same
 * epoch is believed. A datastore that can't be read keeps serving what's cached.
 */
class AdvertisedEndpoints {

    private static final Log log = LogFactory.getLog(AdvertisedEndpoints.class);

    private record Cached(List<String> urls, Epoch epoch, Instant fetchedAt) {
    }

    private final SystemEphemeralDatastore datastore;
    private final Duration refreshInterval;
    private final InstantSource clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicInteger rotation = new AtomicInteger();

    AdvertisedEndpoints(SystemEphemeralDatastore datastore, Duration refreshInterval, InstantSource clock) {
        this.datastore = datastore;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    /** The next base URL to call for {@code service@version}, or {@code null} if nobody advertises one. */
    String next(String service, int version) {
        List<String> urls = urls(ServiceAdvertisement.key(service, version));
        return urls.isEmpty() ? null : urls.get(Math.floorMod(rotation.getAndIncrement(), urls.size()));
    }

    /**
     * {@code url} was just tried and wasn't there (unreachable, or it no longer serves the service):
     * stop offering it until the advertisements are next read, unless it's the only one left, which is
     * still the best guess there is.
     */
    void failed(String service, int version, String url) {
        cache.computeIfPresent(ServiceAdvertisement.key(service, version), (key, cached) -> {
            List<String> remaining = cached.urls().stream().filter(candidate -> !candidate.equals(url)).toList();
            return remaining.isEmpty() ? cached : new Cached(remaining, cached.epoch(), cached.fetchedAt());
        });
    }

    private List<String> urls(String key) {
        Cached cached = cache.get(key);
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.fetchedAt().plus(refreshInterval))) {
            return cached.urls();
        }
        try {
            SystemEphemeralDatastore.Snapshot snapshot = datastore.read(key);
            List<String> fresh = snapshot.members().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(MemberId::nodeId).thenComparing(MemberId::localName)))
                    .map(member -> ServiceAdvertisement.decode(member.getValue()).url())
                    .filter(url -> url != null && !url.isBlank())
                    .distinct()
                    .toList();
            boolean maybeWiped = fresh.isEmpty() && cached != null && !cached.urls().isEmpty()
                    && !snapshot.epoch().equals(cached.epoch());
            Cached updated = new Cached(maybeWiped ? cached.urls() : fresh, snapshot.epoch(), now);
            cache.put(key, updated);
            return updated.urls();
        } catch (RuntimeException e) {
            if (cached == null) {
                throw e;
            }
            log.warn("Couldn't refresh the advertisements for " + key + "; using the last ones read", e);
            // Not recorded as fetched: the next call tries again.
            return cached.urls();
        }
    }
}
