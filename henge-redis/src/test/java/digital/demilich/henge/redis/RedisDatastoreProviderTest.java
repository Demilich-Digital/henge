package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.StoreUnavailableException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisDatastoreProviderTest {

    private final RedisDatastoreProvider provider = new RedisDatastoreProvider();

    @Test
    void neitherAUriNorClusterNodesIsRefused() {
        assertThatThrownBy(() -> provider.create(Map.<String, String>of()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one of")
                .hasMessageContaining("henge.store.redis.cluster-nodes");
    }

    @Test
    void bothAUriAndClusterNodesIsRefused() {
        var properties = Map.of(
                "henge.store.redis.uri", "redis://localhost:6379",
                "henge.store.redis.cluster-nodes", "redis://localhost:7000");

        assertThatThrownBy(() -> provider.create(properties::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one of");
    }

    /** A host that drops packets, or accepts and never answers, is not "connection refused": it has to time out. */
    @Test
    void aStoreThatNeverAnswersFailsInSecondsNotMinutes() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            var properties = Map.of("henge.store.redis.uri", "redis://localhost:" + silent.getLocalPort());

            long start = System.nanoTime();
            assertThatThrownBy(() -> provider.create(properties::get)).isInstanceOf(StoreUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void theTimeoutPropertyTakesMillisecondsUnitsAndIso() throws Exception {
        for (String timeout : new String[] {"300", "300ms", "1s", "PT1S"}) {
            try (ServerSocket silent = new ServerSocket(0)) {
                var properties = Map.of(
                        "henge.store.redis.uri", "redis://localhost:" + silent.getLocalPort(),
                        "henge.store.redis.timeout", timeout);

                assertThatThrownBy(() -> provider.create(properties::get)).isInstanceOf(StoreUnavailableException.class);
            }
        }
    }

    @Test
    void theTimeoutPropertyReplacesTheDefaultAndTheUrisOwn() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            var properties = Map.of(
                    "henge.store.redis.uri", "redis://localhost:" + silent.getLocalPort() + "?timeout=60s",
                    "henge.store.redis.timeout", "200ms");

            long start = System.nanoTime();
            assertThatThrownBy(() -> provider.create(properties::get)).isInstanceOf(StoreUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void aBadTimeoutIsRefusedNamingTheProperty() {
        for (String bad : new String[] {"soon", "-1", "0", "2h"}) {
            var properties = Map.of("henge.store.redis.uri", "redis://localhost:6379", "henge.store.redis.timeout", bad);

            assertThatThrownBy(() -> provider.create(properties::get))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("henge.store.redis.timeout=" + bad);
        }
    }
}
