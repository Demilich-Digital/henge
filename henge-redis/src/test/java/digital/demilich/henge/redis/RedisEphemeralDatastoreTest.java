package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.EphemeralDatastoreContract;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Against a real Redis in a container; skipped where there's no Docker. */
@Testcontainers(disabledWithoutDocker = true)
class RedisEphemeralDatastoreTest extends EphemeralDatastoreContract {

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

    @Override
    protected SystemEphemeralDatastore store() {
        return store;
    }

    @Override
    protected void advance(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
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
        assertThat(snapshot.epoch().id()).matches("[0-9a-f]{40}/[0-9]+\\.[0-9]+");
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
    void aKeysEpochIsSharedByEveryNodeAndEachKeyHasItsOwn() {
        String key = freshKey();
        String other = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        store.put(other, "a", new byte[] {1}, Duration.ofSeconds(30));
        var epoch = store.read(key).epoch();

        assertThat(otherNode.read(key).epoch()).isEqualTo(epoch);
        assertThat(otherNode.read(other).epoch()).isNotEqualTo(epoch);
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
    void aNodeThatSeesTheClaimsOverItsCapacityIsRefusedItsRenewal() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(key, "a", 40, 100, Duration.ofSeconds(30))).isTrue();

        // Capacity is each node's own: one that believes in 50 isn't let renew what the others' 40 leave no room for.
        assertThat(store.claim(key, "a", 60, 50, Duration.ofSeconds(30))).isFalse();
        // One that believes in 100 renews fine.
        assertThat(otherNode.claim(key, "a", 40, 100, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void aClaimThatChangesItsAmountIsCheckedAsANewOne() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 60, 100, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(key, "a", 40, 100, Duration.ofSeconds(30))).isTrue();

        assertThat(store.claim(key, "a", 61, 100, Duration.ofSeconds(30))).isFalse();
        // Refused, so given up, at what it held.
        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-60));
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
    void aNegativeCapacityIsRejected() {
        assertThatThrownBy(() -> store.claim(freshKey(), "a", 1, -1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClaimBeingGivenUpIsAlwaysGrantedAndReadsAsNegative() {
        String key = freshKey();
        assertThat(store.claim(key, "a", 5, 10, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim(key, "b", 5, 10, Duration.ofSeconds(30))).isTrue();

        // Whatever the others hold: giving up needs no room.
        assertThat(store.claim(key, "a", -5, 1, Duration.ofSeconds(30))).isTrue();

        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-5));
    }

    @Test
    void aClaimBeingGivenUpIsWrittenAgainWhenTheStoreLostIt() {
        String key = freshKey();

        assertThat(store.claim(key, "a", -5, 10, Duration.ofSeconds(30))).isTrue();

        assertThat(store.read(key).members().get(new MemberId(store.nodeId(), "a"))).containsExactly(amount(-5));
    }

    @Test
    void aRefusedRenewalTurnsTheClaimIntoOneBeingGivenUpAndTheNodesStayingAreNotRefusedBecauseOfIt() {
        String key = freshKey();
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

    @Test
    void theProviderIsRegisteredAsRedisAndConnectsToTheConfiguredUri() {
        var providers = java.util.ServiceLoader.load(digital.demilich.henge.core.SystemEphemeralDatastoreProvider.class).stream()
                .map(java.util.ServiceLoader.Provider::get).toList();
        assertThat(providers).singleElement().isInstanceOf(RedisDatastoreProvider.class);
        var provider = providers.get(0);
        assertThat(provider.type()).isEqualTo("redis");

        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        try (var made = (RedisEphemeralDatastore) provider.create(key -> key.equals("henge.store.redis.uri") ? " " + uri + " " : null)) {
            String key = freshKey();
            made.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
            assertThat(store.read(key).members()).containsOnlyKeys(new MemberId(made.nodeId(), "a"));
        }
    }

    @Test
    void theProviderNeedsAUriAndSaysWhichProperty() {
        assertThatThrownBy(() -> new RedisDatastoreProvider().create(key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("henge.store.redis.uri");
    }

    @Test
    void aMalformedUriIsReportedAsSuch() {
        assertThatThrownBy(() -> RedisEphemeralDatastore.connect("not a uri"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a Redis URI");
    }

    private static int i2(String name) {
        return Integer.parseInt(name.substring(1));
    }

    @Test
    void concurrentClaimsFromTwoNodesNeverGrantMoreThanTheCapacity() throws Exception {
        String key = freshKey();
        int claimants = 64;
        int capacity = 10;
        var executor = Executors.newFixedThreadPool(claimants);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < claimants; i++) {
                RedisEphemeralDatastore node = i % 2 == 0 ? store : otherNode;
                String name = "claimant-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    return node.claim(key, name, 1, capacity, Duration.ofSeconds(30));
                }));
            }
            start.countDown();
            int granted = 0;
            for (var result : results) {
                if (result.get()) {
                    granted++;
                }
            }

            assertThat(granted).isEqualTo(capacity);
            assertThat(store.read(key).members()).hasSize(capacity);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aBucketIsSharedAcrossNodesAndRefusesPastItsCapacity() {
        String key = freshKey();
        var limit = RateLimit.perSecond(1, 5);

        assertThat(store.tryAcquire(key, 3, limit).granted()).isTrue();
        assertThat(otherNode.tryAcquire(key, 2, limit).granted()).isTrue();

        assertThat(store.tryAcquire(key, 1, limit).granted()).isFalse();
        assertThat(otherNode.tryAcquire(key, 1, limit).granted()).isFalse();
    }

    @Test
    void aBucketLeaksOnTheServersClock() throws Exception {
        String key = freshKey();
        var limit = new RateLimit(2, 10, Duration.ofSeconds(1));
        assertThat(store.tryAcquire(key, 2, limit).granted()).isTrue();
        assertThat(store.tryAcquire(key, 1, limit).granted()).isFalse();

        sleep(Duration.ofMillis(150));

        assertThat(store.tryAcquire(key, 1, limit).granted()).isTrue();
        sleep(Duration.ofMillis(300));
        assertThat(store.tryAcquire(key, 2, limit).granted()).isTrue();
    }

    @Test
    void aRequestLargerThanTheCapacityIsRefusedAndNegativeIsRejected() {
        var limit = RateLimit.perSecond(1, 5);

        assertThat(store.tryAcquire(freshKey(), 6, limit).granted()).isFalse();
        assertThatThrownBy(() -> store.tryAcquire(freshKey(), -1, limit)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBucketAndMembersMayShareAKey() {
        String key = freshKey();
        store.claim(key, "a", 1, 5, Duration.ofSeconds(30));

        assertThat(store.tryAcquire(key, 5, RateLimit.perSecond(1, 5)).granted()).isTrue();
        assertThat(store.read(key).members()).hasSize(1);
    }

    @Test
    void theLimiterKeepsWorkingWhenRedisForgetsItsScripts() {
        String key = freshKey();
        var limit = RateLimit.perSecond(1, 5);
        assertThat(store.tryAcquire(key, 1, limit).granted()).isTrue();

        // SCRIPT FLUSH, as after a restart: the cached digest is now unknown to the server.
        var client = io.lettuce.core.RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (var connection = client.connect()) {
            connection.sync().scriptFlush();
        } finally {
            client.shutdown();
        }

        assertThat(store.tryAcquire(key, 1, limit).granted()).isTrue();
    }

    @Test
    void concurrentCallersNeverGetMoreThanTheBucketsCapacity() throws Exception {
        String key = freshKey();
        var limit = RateLimit.perSecond(1, 5);
        int callers = 32;
        var executor = Executors.newFixedThreadPool(callers);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return store.tryAcquire(key, 1, limit).granted();
                }));
            }
            start.countDown();
            int granted = 0;
            for (var result : results) {
                if (result.get()) {
                    granted++;
                }
            }

            // One permit a second leaks in while the callers race, so a sixth is allowed only if the race took over a second.
            assertThat(granted).isBetween(5, 6);
        } finally {
            executor.shutdownNow();
        }
    }

    // The epoch: every loss changes it, expiry doesn't

    /** Runs {@code command} against the Redis directly, as an operator or the server itself would. */
    private static <T> T redis(java.util.function.Function<io.lettuce.core.api.sync.RedisCommands<String, String>, T> command) {
        var client = io.lettuce.core.RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (var connection = client.connect()) {
            return command.apply(connection.sync());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void aFlushChangesTheEpoch() {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        var before = store.read(key).epoch();

        redis(commands -> commands.flushall());

        var after = otherNode.read(key);
        assertThat(after.members()).isEmpty();
        assertThat(after.epoch()).isNotEqualTo(before);
        assertThat(store.count(key).epoch()).isEqualTo(after.epoch());
    }

    @Test
    void anEvictedKeyChangesItsEpochAndNoOtherKeys() {
        String evicted = freshKey();
        String kept = freshKey();
        store.claim(evicted, "a", 1, 10, Duration.ofSeconds(30));
        store.put(kept, "a", new byte[] {1}, Duration.ofSeconds(30));
        var evictedBefore = store.read(evicted).epoch();
        var keptBefore = store.read(kept).epoch();

        // Eviction takes a whole key, as DEL does.
        redis(commands -> commands.del("henge:" + evicted));

        assertThat(store.read(evicted).epoch()).isNotEqualTo(evictedBefore);
        assertThat(store.read(kept).epoch()).isEqualTo(keptBefore);
    }

    @Test
    void membersLapsingKeepTheEpoch() throws InterruptedException {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofMillis(100));
        store.claim(freshKey(), "a", 1, 10, Duration.ofMillis(100));
        var before = store.read(key).epoch();

        sleep(Duration.ofMillis(300));

        var after = store.read(key);
        assertThat(after.members()).isEmpty();
        assertThat(after.epoch()).isEqualTo(before);
        assertThat(store.count(key).live()).isZero();
        assertThat(store.sample(key, 5).members()).isEmpty();
    }

    @Test
    void theTokenIsNeverAMember() {
        String claimed = freshKey();
        assertThat(store.claim(claimed, "a", 5, 10, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(claimed, "a", 5, 10, Duration.ofSeconds(30))).isTrue();
        assertThat(otherNode.claim(claimed, "b", 1, 10, Duration.ofSeconds(30))).as("the token holds nothing").isFalse();

        assertThat(store.read(claimed).members()).hasSize(2);
        assertThat(store.count(claimed).live()).isEqualTo(2);
        assertThat(store.sample(claimed, 1).members()).hasSize(1);
        assertThat(store.sample(claimed, 10).members()).hasSize(2);
    }

    @Test
    void onlyAWrittenMemberLeavesAKeyAndThatKeyLapses() {
        String read = freshKey();
        String counted = freshKey();
        String sampled = freshKey();
        String removed = freshKey();
        String refused = freshKey();
        String put = freshKey();
        String claimed = freshKey();
        store.read(read);
        store.count(counted);
        store.sample(sampled, 1);
        store.remove(removed, "a");
        store.claim(refused, "a", 2, 1, Duration.ofSeconds(30));
        store.put(put, "a", new byte[] {1}, Duration.ofSeconds(30));
        store.claim(claimed, "a", 1, 1, Duration.ofSeconds(30));

        for (String key : java.util.List.of(read, counted, sampled, removed, refused)) {
            long exists = redis(commands -> commands.exists("henge:" + key));
            assertThat(exists).as(key).isZero();
        }
        for (String key : java.util.List.of(put, claimed)) {
            long ttl = redis(commands -> commands.pttl("henge:" + key));
            assertThat(ttl).as(key).isPositive().isLessThanOrEqualTo(RedisEphemeralDatastore.RETENTION.toMillis());
        }
    }

    @Test
    void aReadLeavesTheHashsTtlAsTheLastWriteSetIt() throws InterruptedException {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        sleep(Duration.ofMillis(50));
        long before = redis(commands -> commands.pttl("henge:" + key));

        store.read(key);
        store.count(key);
        store.sample(key, 1);

        long after = redis(commands -> commands.pttl("henge:" + key));
        assertThat(after).isLessThanOrEqualTo(before);
    }

    @Test
    void theFirstWriteToAnEmptyKeyIsANewEpoch() {
        String key = freshKey();
        var empty = store.read(key).epoch();

        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));

        assertThat(store.read(key).epoch()).isNotEqualTo(empty);
    }

    @Test
    void aMemberLongerThanTheRetentionKeepsItsHash() {
        String key = freshKey();
        store.put(key, "a", new byte[] {1}, RedisEphemeralDatastore.RETENTION.multipliedBy(2));
        store.read(key);

        long ttl = redis(commands -> commands.pttl("henge:" + key));
        assertThat(ttl).isGreaterThan(RedisEphemeralDatastore.RETENTION.toMillis());
    }
}
