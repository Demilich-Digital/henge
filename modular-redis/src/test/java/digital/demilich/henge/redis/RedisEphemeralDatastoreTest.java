package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Against a real Redis in a container; skipped where there's no Docker. */
@Testcontainers(disabledWithoutDocker = true)
class RedisEphemeralDatastoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    static RedisEphemeralDatastore store;
    static RedisEphemeralDatastore otherNode;

    @BeforeAll
    static void connect() {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        store = RedisEphemeralDatastore.connect(uri);
        otherNode = RedisEphemeralDatastore.connect(uri);
    }

    @AfterAll
    static void disconnect() {
        store.close();
        otherNode.close();
    }

    /** A key nobody else is using, so tests don't see each other's members. */
    private static String freshKey() {
        return "test:" + UUID.randomUUID();
    }

    private static byte[] amount(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @Test
    void aWrittenMemberIsReadBackUnderThisNodesId() {
        String key = freshKey();
        store.put(key, "a", new byte[] {1, 0, 2}, Duration.ofSeconds(30));

        var snapshot = store.read(key);

        assertThat(snapshot.members()).containsOnlyKeys(new MemberId(store.nodeId(), "a"));
        assertThat(snapshot.members().get(new MemberId(store.nodeId(), "a"))).containsExactly(1, 0, 2);
        assertThat(snapshot.epoch().id()).hasSize(40);
    }

    @Test
    void aLocalNameMayContainSlashesAndBinaryValuesSurvive() {
        String key = freshKey();
        byte[] binary = {0, -1, 127, -128, 10, 13};
        store.put(key, "order-service@1/x", binary, Duration.ofSeconds(30));

        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "order-service@1/x"))).containsExactly(binary);
    }

    @Test
    void readingAnUnknownKeyIsEmptyButStillHasAnEpoch() {
        var snapshot = store.read(freshKey());

        assertThat(snapshot.members()).isEmpty();
        assertThat(snapshot.epoch().id()).isNotBlank();
    }

    @Test
    void theEpochIsTheServersAndSharedByEveryNode() {
        assertThat(store.read(freshKey()).epoch()).isEqualTo(otherNode.read(freshKey()).epoch());
    }

    @Test
    void aMemberExpiresOnItsOwnAndRenewingExtendsIt() throws Exception {
        String key = freshKey();
        store.put(key, "short", new byte[] {1}, Duration.ofMillis(400));
        store.put(key, "renewed", new byte[] {1}, Duration.ofMillis(400));

        sleep(Duration.ofMillis(250));
        store.put(key, "renewed", new byte[] {2}, Duration.ofMillis(400));
        sleep(Duration.ofMillis(250));

        assertThat(store.read(key).members()).containsOnlyKeys(new MemberId(store.nodeId(), "renewed"));
        sleep(Duration.ofMillis(300));
        assertThat(store.read(key).members()).isEmpty();
    }

    @Test
    void aMemberWithALongTtlOutlivesTheKeysEarlierShorterOne() throws Exception {
        String key = freshKey();
        store.put(key, "short", new byte[] {1}, Duration.ofMillis(300));
        store.put(key, "long", new byte[] {1}, Duration.ofSeconds(30));

        sleep(Duration.ofMillis(500));

        assertThat(store.read(key).members()).containsOnlyKeys(new MemberId(store.nodeId(), "long"));
    }

    @Test
    void removeDropsOnlyThatMember() {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        store.put(key, "b", new byte[] {1}, Duration.ofSeconds(30));

        store.remove(key, "a");
        store.remove(key, "never-there");

        assertThat(store.read(key).members()).containsOnlyKeys(new MemberId(store.nodeId(), "b"));
    }

    @Test
    void eachNodeSeesTheOthersMembersButCanOnlyWriteItsOwn() {
        String key = freshKey();
        store.put(key, "host", new byte[] {1}, Duration.ofSeconds(30));
        otherNode.put(key, "host", new byte[] {2}, Duration.ofSeconds(30));

        assertThat(store.read(key).members()).containsOnlyKeys(
                new MemberId(store.nodeId(), "host"), new MemberId(otherNode.nodeId(), "host"));

        otherNode.remove(key, "host");
        assertThat(store.read(key).members()).containsOnlyKeys(new MemberId(store.nodeId(), "host"));
    }

    @Test
    void aNonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> store.put(freshKey(), "a", new byte[0], Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(freshKey(), "a", 1, 5, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimsAreGrantedUpToCapacityAndRefusedBeyondItAcrossNodes() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(key, "a", 40, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim(key, "b", 1, 100, Duration.ofSeconds(30))).isFalse();
        assertThat(otherNode.claim(key, "b", 1, 100, Duration.ofSeconds(30))).isFalse();

        var members = store.read(key).members();
        assertThat(members).containsOnlyKeys(new MemberId(store.nodeId(), "a"), new MemberId(otherNode.nodeId(), "a"));
        assertThat(members.get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(60));
    }

    @Test
    void renewingAClaimDoesNotCountAgainstItselfAndMayChangeItsAmount() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim(key, "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim(key, "a", 50, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(key, "b", 50, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void anExpiredClaimFreesItsCapacity() throws Exception {
        String key = freshKey();
        assertThat(store.claim(key, "a", 100, 100, Duration.ofMillis(300))).isTrue();
        assertThat(otherNode.claim(key, "b", 100, 100, Duration.ofSeconds(30))).isFalse();

        sleep(Duration.ofMillis(500));

        assertThat(otherNode.claim(key, "b", 100, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aRemovedClaimFreesItsCapacity() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 100, 100, Duration.ofSeconds(30))).isTrue();
        store.remove(key, "a");

        assertThat(otherNode.claim(key, "b", 100, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aRefusedClaimWritesNothing() {
        String key = freshKey();

        assertThat(store.claim(key, "a", 101, 100, Duration.ofSeconds(30))).isFalse();

        assertThat(store.read(key).members()).isEmpty();
    }

    @Test
    void aNegativeAmountOrCapacityIsRejected() {
        assertThatThrownBy(() -> store.claim(freshKey(), "a", -1, 100, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.claim(freshKey(), "a", 1, -1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimingAKeyWrittenWithPutFailsLoudly() {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));

        assertThatThrownBy(() -> store.claim(key, "b", 1, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claims or for put");
    }

    @Test
    void manyNodesRacingForTheLastOfACapacityNeverOverGrant() throws Exception {
        String key = freshKey();
        int claimants = 20;
        var granted = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(claimants);
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < claimants; i++) {
            String name = "c" + i;
            done.add(pool.submit(() -> {
                start.await();
                if ((i2(name) % 2 == 0 ? store : otherNode).claim(key, name, 10, 100, Duration.ofSeconds(30))) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : done) {
            future.get();
        }
        pool.shutdown();

        assertThat(granted.get()).isEqualTo(10);
        assertThat(store.read(key).members()).hasSize(10);
    }

    private static int i2(String name) {
        return Integer.parseInt(name.substring(1));
    }
}
