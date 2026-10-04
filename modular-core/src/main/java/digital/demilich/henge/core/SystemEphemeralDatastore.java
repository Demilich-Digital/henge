package digital.demilich.henge.core;

import java.time.Duration;
import java.util.Map;

/**
 * Henge's own shared, expiring state: what the process knows about the rest of the cluster, and
 * what it claims from it. Not an application datastore, and not for application data. Every entry
 * expires, and the whole thing may be wiped at any time (see {@link Epoch}).
 *
 * <p>The model is a map of {@code key -> {member -> (value, expiresAt)}} in which <b>each member has
 * exactly one writer</b>: the node that owns it. A node can only write members under its own
 * {@link #nodeId()}, so there is nothing to conflict over and no consensus to reach. Readers see the
 * live members of a key across all writers. Time-to-live is relative, and the deadline is computed
 * on the store's own clock, so a writer's clock skew is irrelevant.
 *
 * <p>{@link #claim} is the one operation that must be atomic per key. It still isn't consensus: it
 * needs only whoever serializes operations on that one key to be unique, and where that is briefly
 * not so, the result is bounded overshoot of a soft cap.
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
     * Atomically: if the sum of the live members' amounts under {@code key}, excluding this node's
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
