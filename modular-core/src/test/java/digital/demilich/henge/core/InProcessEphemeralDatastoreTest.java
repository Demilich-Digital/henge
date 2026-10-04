package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InProcessEphemeralDatastoreTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    private static byte[] amount(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    @Test
    void aWrittenMemberIsReadBackUnderThisNodesId() {
        store.put("k", "a", new byte[] {1, 2}, Duration.ofSeconds(30));

        var snapshot = store.read("k");

        assertThat(snapshot.members()).containsOnlyKeys(new MemberId(store.nodeId(), "a"));
        assertThat(snapshot.members().get(new MemberId(store.nodeId(), "a"))).containsExactly(1, 2);
        assertThat(snapshot.epoch().id()).isEqualTo(store.nodeId());
    }

    @Test
    void readingAnUnknownKeyIsEmpty() {
        assertThat(store.read("nothing").members()).isEmpty();
    }

    @Test
    void aMemberExpiresAfterItsTtlAndRenewingExtendsIt() {
        store.put("k", "a", new byte[] {1}, Duration.ofSeconds(30));
        store.put("k", "b", new byte[] {1}, Duration.ofSeconds(30));

        advance(Duration.ofSeconds(20));
        store.put("k", "a", new byte[] {2}, Duration.ofSeconds(30));
        advance(Duration.ofSeconds(20));

        assertThat(store.read("k").members()).containsOnlyKeys(new MemberId(store.nodeId(), "a"));
        advance(Duration.ofSeconds(10));
        assertThat(store.read("k").members()).isEmpty();
    }

    @Test
    void removeDropsOnlyThatMember() {
        store.put("k", "a", new byte[] {1}, Duration.ofSeconds(30));
        store.put("k", "b", new byte[] {1}, Duration.ofSeconds(30));

        store.remove("k", "a");
        store.remove("k", "never-there");

        assertThat(store.read("k").members()).containsOnlyKeys(new MemberId(store.nodeId(), "b"));
    }

    @Test
    void theStoreKeepsItsOwnCopyOfValues() {
        byte[] value = {1};
        store.put("k", "a", value, Duration.ofSeconds(30));
        value[0] = 9;
        store.read("k").members().values().iterator().next()[0] = 9;

        assertThat(store.read("k").members().values()).singleElement().isEqualTo(new byte[] {1});
    }

    @Test
    void aNonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> store.put("k", "a", new byte[0], Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim("k", "a", 1, 5, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimsAreGrantedUpToCapacityAndRefusedBeyondIt() {
        assertThat(store.claim("lease", "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "b", 40, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "c", 1, 100, Duration.ofSeconds(30))).isFalse();

        assertThat(store.read("lease").members()).containsOnlyKeys(
                new MemberId(store.nodeId(), "a"), new MemberId(store.nodeId(), "b"));
        assertThat(store.read("lease").members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(60));
    }

    @Test
    void renewingAClaimDoesNotCountAgainstItself() {
        assertThat(store.claim("lease", "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "b", 1, 100, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void anExpiredClaimFreesItsCapacity() {
        assertThat(store.claim("lease", "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        advance(Duration.ofSeconds(30));

        assertThat(store.claim("lease", "b", 100, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aRemovedClaimFreesItsCapacity() {
        assertThat(store.claim("lease", "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        store.remove("lease", "a");

        assertThat(store.claim("lease", "b", 100, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aRefusedClaimWritesNothing() {
        assertThat(store.claim("lease", "a", 101, 100, Duration.ofSeconds(30))).isFalse();

        assertThat(store.read("lease").members()).isEmpty();
    }

    @Test
    void aNegativeAmountOrCapacityIsRejected() {
        assertThatThrownBy(() -> store.claim("lease", "a", -1, 100, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim("lease", "a", 1, -1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimingAKeyWrittenWithPutFailsLoudly() {
        store.put("k", "a", new byte[] {1}, Duration.ofSeconds(30));

        assertThatThrownBy(() -> store.claim("k", "b", 1, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claims or for put");
    }

    @Test
    void twoStoresAreTwoNodes() {
        assertThat(new InProcessEphemeralDatastore().nodeId()).isNotEqualTo(new InProcessEphemeralDatastore().nodeId());
    }

    @Test
    void theSystemClockConstructorWorks() {
        var real = new InProcessEphemeralDatastore(InstantSource.system());
        real.put("k", "a", new byte[] {1}, Duration.ofMinutes(1));

        assertThat(real.read("k").members()).hasSize(1);
    }
}
