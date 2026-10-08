package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.StoreUnavailableException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A Redis that runs out of memory, in a container of its own, since these tests change its
 * {@code maxmemory}. Another client fills it with keys of its own, as a shared Redis would be filled.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisMemoryPressureTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private static final Duration SAMPLE = Duration.ofMillis(100);
    private static final String FILLER = "x".repeat(100_000);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static RedisCommands<String, String> other;
    static RedisEphemeralDatastore store;

    @BeforeAll
    static void connect() {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        client = RedisClient.create(uri);
        connection = client.connect();
        other = connection.sync();
        store = RedisEphemeralDatastore.connect(uri, null, SAMPLE);
    }

    @AfterAll
    static void disconnect() {
        store.close();
        connection.close();
        client.shutdown();
    }

    @AfterEach
    void makeRoom() {
        other.configSet("maxmemory", "0");
        other.configSet("maxmemory-policy", "noeviction");
        other.flushall();
    }

    /** A limit a couple of megabytes above what the server uses now. */
    private static void limitMemory(String policy) {
        long used = Long.parseLong(other.info("memory").lines()
                .filter(line -> line.startsWith("used_memory:")).findFirst().orElseThrow().substring("used_memory:".length()).trim());
        other.configSet("maxmemory-policy", policy);
        other.configSet("maxmemory", Long.toString(used + 2_000_000));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waiting for: " + what).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private static boolean unavailable(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (StoreUnavailableException e) {
            return true;
        }
    }

    @Test
    void aRedisThatIsEvictingIsUnreachableUntilItStops() throws Exception {
        String key = "lease:" + UUID.randomUUID();
        store.claim(key, "a", 1, 10, Duration.ofSeconds(30));
        limitMemory("allkeys-random");

        AtomicBoolean filling = new AtomicBoolean(true);
        Thread filler = Thread.ofVirtual().start(() -> {
            // Its own connection: the shared one is the test's, and a sync connection isn't for two threads' timing.
            try (var own = client.connect()) {
                for (int i = 0; filling.get(); i++) {
                    own.sync().set("filler:" + i, FILLER);
                }
            }
        });
        try {
            await("the store to treat Redis as unreachable", () -> unavailable(() -> store.read(key)));
            assertThatThrownBy(() -> store.claim(key, "a", 1, 10, Duration.ofSeconds(30)))
                    .isInstanceOf(StoreUnavailableException.class).hasMessageContaining("evicting");
            assertThatThrownBy(() -> store.tryAcquire("rate:" + key, 1, digital.demilich.henge.core.RateLimit.perSecond(1, 1)))
                    .isInstanceOf(StoreUnavailableException.class);
        } finally {
            filling.set(false);
            filler.join();
        }

        await("the store to be reachable once Redis stops evicting", () -> !unavailable(() -> store.read(key)));
    }

    @Test
    void aFullRedisUnderNoevictionRefusesWritesAsUnreachableAndSaysItIsFull() throws Exception {
        String key = "adv:" + UUID.randomUUID();
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        // Well under what it already holds, so it stays full whatever a refused command freed.
        other.configSet("maxmemory-policy", "noeviction");
        other.configSet("maxmemory", "100kb");
        assertThatThrownBy(() -> other.set("filler", FILLER)).hasMessageStartingWith("OOM");

        assertThatThrownBy(() -> store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30)))
                .isInstanceOf(StoreUnavailableException.class).hasMessageContaining("full");
        // Redis refuses a script only when it writes, so what is already there can still be read.
        assertThat(store.read(key).members()).hasSize(1);
        assertThat(store.count(key).live()).isEqualTo(1);

        other.configSet("maxmemory", "0");
        store.put(key, "a", new byte[] {1}, Duration.ofSeconds(30));
        assertThat(store.read(key).members()).hasSize(1);
    }
}
