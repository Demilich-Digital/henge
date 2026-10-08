package digital.demilich.henge.core;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@link SystemEphemeralDatastore} that lives inside this process: the default, and the only one
 * a monolith needs, since there is no one else to share anything with. Expiry is lazy: an expired
 * member is dropped the next time its key is touched, so nothing runs in the background. One lock
 * covers everything, which also makes {@link #claim} atomic.
 */
public final class InProcessEphemeralDatastore implements SystemEphemeralDatastore {

    private final String nodeId = UUID.randomUUID().toString();
    private final Epoch epoch = new Epoch(nodeId);
    private final InstantSource clock;
    private final Map<String, Map<String, Entry>> keys = new HashMap<>();
    private final Map<String, Bucket> buckets = new HashMap<>();

    public InProcessEphemeralDatastore() {
        this(InstantSource.system());
    }

    public InProcessEphemeralDatastore(InstantSource clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public synchronized void put(String key, String localName, byte[] value, Duration ttl) {
        Objects.requireNonNull(value, "value");
        liveMembers(key, true).put(localName, new Entry(value.clone(), deadline(ttl)));
    }

    @Override
    public synchronized void remove(String key, String localName) {
        Map<String, Entry> members = liveMembers(key, false);
        if (members != null) {
            members.remove(localName);
            if (members.isEmpty()) {
                keys.remove(key);
            }
        }
    }

    @Override
    public synchronized Snapshot read(String key) {
        Map<String, Entry> members = liveMembers(key, false);
        Map<MemberId, byte[]> result = new HashMap<>();
        if (members != null) {
            members.forEach((localName, entry) -> result.put(new MemberId(nodeId, localName), entry.value().clone()));
        }
        return new Snapshot(Map.copyOf(result), epoch);
    }

    @Override
    public synchronized boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative, got " + capacity);
        }
        Instant deadline = deadline(ttl);
        Map<String, Entry> members = liveMembers(key, true);
        if (amount < 0) {
            // Leaving: needs no room, and counts for nothing against a renewal.
            members.put(localName, new Entry(ByteBuffer.allocate(Integer.BYTES).putInt(amount).array(), deadline));
            return true;
        }
        Entry own = members.get(localName);
        int ownAmount = own == null ? 0 : amountOf(key, localName, own);
        boolean renewing = own != null && ownAmount >= 0;
        long claimedByOthers = 0;
        for (Map.Entry<String, Entry> member : members.entrySet()) {
            if (!member.getKey().equals(localName)) {
                int held = amountOf(key, member.getKey(), member.getValue());
                // A claim being renewed is not asked to make room for those who are leaving; a new one is.
                claimedByOthers += renewing && held < 0 ? 0 : Math.abs((long) held);
            }
        }
        if (claimedByOthers + amount > capacity) {
            if (renewing) {
                members.put(localName, new Entry(ByteBuffer.allocate(Integer.BYTES).putInt(-ownAmount).array(), deadline));
            } else if (members.isEmpty()) {
                keys.remove(key);
            }
            return false;
        }
        members.put(localName, new Entry(ByteBuffer.allocate(Integer.BYTES).putInt(amount).array(), deadline));
        return true;
    }

    @Override
    public synchronized Acquisition tryAcquire(String key, int amount, RateLimit limit) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amount);
        }
        // Levels are in units of 1/period of a permit, so leaking is exact: each elapsed millisecond
        // drains `permits` units, and one permit is `period` units.
        long period = limit.periodMillis();
        long capacity = limit.capacity() * period;
        long now = clock.instant().toEpochMilli();
        Bucket bucket = buckets.get(key);
        long level = 0;
        if (bucket != null) {
            long elapsed = Math.min(Math.max(0, now - bucket.at()), capacity);
            level = Math.max(0, bucket.level() - elapsed * limit.permits());
            now = Math.max(now, bucket.at());
        }
        if (level + amount * period > capacity) {
            if (level == 0) {
                buckets.remove(key);
            }
            return Acquisition.refused(Acquisition.untilOnePermitFits(level, limit));
        }
        level += amount * period;
        if (level == 0) {
            buckets.remove(key);
        } else {
            buckets.put(key, new Bucket(level, now));
        }
        return Acquisition.GRANTED;
    }

    @Override
    public synchronized Count count(String key) {
        Map<String, Entry> members = liveMembers(key, false);
        return new Count(members == null ? 0 : members.size(), epoch);
    }

    /** The key's live members, with the expired ones dropped; null if there are none and {@code create} is false. */
    private Map<String, Entry> liveMembers(String key, boolean create) {
        Map<String, Entry> members = keys.get(key);
        if (members == null) {
            return create ? keys.computeIfAbsent(key, k -> new HashMap<>()) : null;
        }
        Instant now = clock.instant();
        for (Iterator<Entry> it = members.values().iterator(); it.hasNext(); ) {
            if (!it.next().expiresAt().isAfter(now)) {
                it.remove();
            }
        }
        if (members.isEmpty() && !create) {
            keys.remove(key);
            return null;
        }
        return members;
    }

    private Instant deadline(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        return clock.instant().plus(ttl);
    }

    private static int amountOf(String key, String localName, Entry entry) {
        if (entry.value().length != Integer.BYTES) {
            throw new IllegalStateException("Member '" + localName + "' of '" + key
                    + "' was written with put, but the key is being claimed: a key is for claims or for put, never both.");
        }
        return SystemEphemeralDatastore.claimedAmount(entry.value());
    }

    /** A bucket's whole state: how full it was, and when. */
    private record Bucket(long level, long at) {
    }

    private record Entry(byte[] value, Instant expiresAt) {
    }
}
