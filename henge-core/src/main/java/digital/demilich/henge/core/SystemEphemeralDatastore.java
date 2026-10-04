package digital.demilich.henge.core;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

/**
 * Henge's own shared, expiring state: what the process knows about the rest of the cluster, and
 * what it claims from it. Not an application datastore, and not for application data. Every entry
 * expires, and the whole thing may be wiped at any time (see {@link Epoch}).
 *
 * <p>The state is convergent: a key has many writers, and copies of it that diverge merge back into
 * one answer with no coordinator. Members ({@code key -> {member -> (value, expiresAt)}}) belong to
 * the node that wrote them: a node can only write members under its own {@link #nodeId()}, so writers
 * never overwrite each other, and copies merge by union. Readers see the live members of a key across
 * all writers. A bucket ({@link #tryAcquire}) is shared by its writers, and copies merge by taking the
 * highest level. Time-to-live is relative, and the deadline is computed on the store's own clock, so a
 * writer's clock skew is irrelevant.
 *
 * <p>{@link #claim} and {@link #tryAcquire} are atomic within a copy of a key. Across copies, an
 * incomplete view only ever under-counts (fewer claimed members, a lower bucket level), so both err
 * toward granting too much, by a bounded amount, and never refuse what fits. Callers configure their
 * capacities as an intentional underestimate of the real limit, so an over-grant lands in the margin.
 * Nothing here needs consensus.
 *
 * <p>Implementations must be thread-safe. See {@code docs/design/self-orchestration.md}.
 */
public interface SystemEphemeralDatastore {

    /** Identifies this node for the life of the process. A restarted process is a new node. */
    String nodeId();

    /** Writes, or renews, this node's member {@code localName} under {@code key}. */
    void put(String key, String localName, byte[] value, Duration ttl);

    /** Removes this node's member early, for graceful deregistration. A no-op if it isn't there. */
    void remove(String key, String localName);

    /** The live members of {@code key}, across all writers, plus the epoch of the storage that answered. */
    Snapshot read(String key);

    /**
     * Atomically within a copy of the key: if the sum of the live members' amounts under {@code key}, excluding this node's
     * own member {@code localName} (so renewing never fails against itself), plus {@code amount} is at
     * most {@code capacity}, writes (or renews) this node's member and returns {@code true};
     * otherwise writes nothing and returns {@code false}.
     *
     * <p>A claimed member's value is its amount, as a 4-byte big-endian {@code int}, so {@link #read}
     * shows what is claimed. A key is for claims or for {@link #put}, never both.
     *
     * @throws IllegalArgumentException if {@code amount} or {@code capacity} is negative
     */
    boolean claim(String key, String localName, int amount, int capacity, Duration ttl);

    /**
     * Leaks the bucket at {@code key} for the time since it was last touched, then, if {@code amount}
     * more permits fit under the limit's capacity, adds them and returns {@code true}; otherwise changes
     * nothing and returns {@code false}. The check and the take are atomic within a copy of the bucket.
     * A bucket nobody has touched is empty, and one that has drained completely is forgotten, so there
     * is nothing to clean up.
     *
     * <p>A bucket is one level shared by every node that draws on it, not a member. Copies of it merge
     * by taking the highest level, each leaked to now, so a copy that missed some takes reads low, never
     * high: the rate can overshoot by a bounded amount, and a call it should allow is never refused. The
     * leak is computed on the store's own clock. Its keyspace is separate from the members', so a key
     * may be used for a bucket and for members at once.
     *
     * @throws IllegalArgumentException if {@code amount} is negative
     */
    boolean tryAcquire(String key, int amount, RateLimit limit);

    /** The amount a member written by {@link #claim} holds, decoded from its value. */
    static int claimedAmount(byte[] value) {
        if (value.length != Integer.BYTES) {
            throw new IllegalArgumentException("Not a claimed amount: " + value.length + " bytes, expected " + Integer.BYTES);
        }
        return ByteBuffer.wrap(value).getInt();
    }

    /** A member's identity: the node that owns it, and its name under that node. */
    record MemberId(String nodeId, String localName) {
    }

    /**
     * Identifies the storage that answered a read. A different epoch from one read to the next means
     * the data may have been wiped, which is how a reader tells "nobody is there" from "the store
     * just restarted".
     */
    record Epoch(String id) {
    }

    /** The live members of a key as of one read. The byte arrays are the reader's own copies. */
    record Snapshot(Map<MemberId, byte[]> members, Epoch epoch) {
    }
}
