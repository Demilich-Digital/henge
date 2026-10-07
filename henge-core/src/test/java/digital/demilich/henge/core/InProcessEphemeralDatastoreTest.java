package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    void aNodeThatSeesTheClaimsOverItsCapacityIsRefusedItsRenewal() {
        assertThat(store.claim("lease", "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "b", 40, 100, Duration.ofSeconds(30))).isTrue();

        // Capacity is each node's own: one that believes in 50 isn't let renew what the others' 40 leave no room for.
        assertThat(store.claim("lease", "a", 60, 50, Duration.ofSeconds(30))).isFalse();
        // One that believes in 100 renews fine.
        assertThat(store.claim("lease", "b", 40, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aClaimThatChangesItsAmountIsCheckedAsANewOne() {
        assertThat(store.claim("lease", "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease", "b", 40, 100, Duration.ofSeconds(30))).isTrue();

        assertThat(store.claim("lease", "a", 61, 100, Duration.ofSeconds(30))).isFalse();
        // Refused, so given up, at what it held.
        assertThat(store.read("lease").members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-60));
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
    void aNegativeCapacityIsRejected() {
        assertThatThrownBy(() -> store.claim("lease", "a", 1, -1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClaimBeingGivenUpIsAlwaysGrantedAndReadsAsNegative() {
        String key = "lease";
        assertThat(store.claim(key, "a", 5, 10, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim(key, "b", 5, 10, Duration.ofSeconds(30))).isTrue();

        // Whatever the others hold: giving up needs no room.
        assertThat(store.claim(key, "a", -5, 1, Duration.ofSeconds(30))).isTrue();

        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-5));
    }

    @Test
    void aClaimBeingGivenUpIsWrittenAgainWhenTheStoreLostIt() {
        String key = "lease";

        assertThat(store.claim(key, "a", -5, 10, Duration.ofSeconds(30))).isTrue();

        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-5));
    }

    @Test
    void aRefusedRenewalTurnsTheClaimIntoOneBeingGivenUpAndTheNodesStayingAreNotRefusedBecauseOfIt() {
        String key = "lease";
        // Three claims of 5, made by nodes that believe in a capacity of 15.
        for (String node : new String[] {"a", "b", "n"}) {
            assertThat(store.claim(key, node, 5, 15, Duration.ofSeconds(30))).isTrue();
        }

        // "a" believes in 10, and is refused; so is turned into a claim being given up, in the same step.
        assertThat(store.claim(key, "a", 5, 10, Duration.ofSeconds(30))).isFalse();
        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-5));
        // "b" believes in 10 too, and now isn't refused: a renewal isn't asked to make room for one that is going.
        assertThat(store.claim(key, "b", 5, 10, Duration.ofSeconds(30))).isTrue();
        // A new claim is: what is going is in use until it has gone.
        assertThat(store.claim(key, "c", 5, 10, Duration.ofSeconds(30))).isFalse();
        store.remove(key, "a");
        assertThat(store.claim(key, "c", 5, 10, Duration.ofSeconds(30))).isFalse();
        assertThat(store.claim(key, "c", 5, 15, Duration.ofSeconds(30))).isTrue();
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

    @Test
    void concurrentClaimsNeverGrantMoreThanTheCapacity() throws Exception {
        int claimants = 64;
        int capacity = 10;
        var executor = Executors.newFixedThreadPool(claimants);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < claimants; i++) {
                String name = "claimant-" + i;
                Callable<Boolean> claim = () -> {
                    start.await();
                    return store.claim("pool", name, 1, capacity, Duration.ofSeconds(30));
                };
                results.add(executor.submit(claim));
            }
            start.countDown();
            int granted = 0;
            for (var result : results) {
                if (result.get()) {
                    granted++;
                }
            }

            assertThat(granted).isEqualTo(capacity);
            assertThat(store.read("pool").members()).hasSize(capacity);
        } finally {
            executor.shutdownNow();
        }
    }
}
