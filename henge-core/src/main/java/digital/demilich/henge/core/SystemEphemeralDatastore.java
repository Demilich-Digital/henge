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
 * <p>Each key stands alone. A key may be unreachable, or lost, while others are fine, and nothing may be
 * assumed about which keys fail together: that is the store's business.
 *
 * <p>An operation that can't be completed throws a {@link RuntimeException}, a
 * {@link StoreUnavailableException} where the store can tell it is unreachable, and an
 * {@link IllegalArgumentException} only for the caller's own mistake. An operation that throws may or
 * may not have taken effect. Whatever it wrote expires like anything else.
 *
 * <p>Implementations must be thread-safe. See {@code docs/ephemeral-store.md}.
 */
public interface SystemEphemeralDatastore {

    /** Identifies this node for the life of the process. A restarted process is a new node. */
    String nodeId();

    /** Writes, or renews, this node's member {@code localName} under {@code key}. */
    void put(String key, String localName, byte[] value, Duration ttl);

    /** Removes this node's member early, for graceful deregistration. A no-op if it isn't there. */
    void remove(String key, String localName);

    /** The live members of {@code key}, across all writers, plus the key's {@link Epoch}. */
    Snapshot read(String key);

    /**
     * Atomically within a copy of the key: if the sum of the live members' amounts under {@code key}, excluding this node's
     * own member {@code localName} (so renewing never fails against itself), plus {@code amount} is at
     * most {@code capacity}, writes (or renews) this node's member and returns {@code true};
     * otherwise returns {@code false}.
     *
     * <p>The capacity is the caller's: each node is configured with its own, so during a rollout that changes
     * it, nodes differ on it. A renewal is checked like a claim, so a node that finds the claims in the store over
     * the capacity it believes in is refused. A claim that is gone (it lapsed, or the store was wiped) is checked
     * the same way, so the store is never over any capacity at the moment a claim is made.
     *
     * <p>A <b>negative</b> {@code amount} is a claim being given up: it is always granted, it writes (or renews, or
     * re-creates after a wipe) this node's member with that negative amount, and the node keeps writing it until it
     * has finished, which it ends with {@link #remove}. Its magnitude is still what the node holds, and it counts
     * against a <em>new</em> claim, since the resource is in use until the node has closed it, but not against a
     * <em>renewal</em>, so the nodes staying are not asked to make room for the one that is going. A renewal that is
     * refused turns the member into one being given up, in the same step that refused it, so that of several
     * nodes refused together, those refused after the first are not.
     *
     * <p>A claimed member's value is its amount, as a 4-byte big-endian signed {@code int}, so {@link #read}
     * shows what is claimed and what is being given up. A key is for claims or for {@link #put}, never both.
     *
     * @throws IllegalArgumentException if {@code capacity} is negative
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

    /** The amount a member written by {@link #claim} holds, decoded from its value; negative if it is being given up. */
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
     * A key's epoch, which changes whenever its members may have been lost other than by expiry or
     * {@link #remove}: the store restarting, failing over to a copy that missed writes, being cleared, or
     * the key moving to storage that didn't have it. This is how a reader tells "nobody is there" from
     * "the store just lost them". It is per key, so two keys may report different epochs, and it may
     * change without a loss (a reader then waits for nothing).
     *
     * <p>It must change for every way the store can lose data in operation, including the ones an operator
     * causes (clearing it) and the ones it does on its own (evicting under memory pressure): they will all
     * happen over a cluster's life. Healing never depends on it, since a consumer survives a loss it wasn't
     * told of as early expiry, only without the warning that keeps the over-grant window small.
     */
    record Epoch(String id) {
    }

    /** The live members of a key as of one read. The byte arrays are the reader's own copies. */
    record Snapshot(Map<MemberId, byte[]> members, Epoch epoch) {
    }
}
