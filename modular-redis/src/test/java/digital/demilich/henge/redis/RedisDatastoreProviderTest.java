package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisDatastoreProviderTest {

    private final RedisDatastoreProvider provider = new RedisDatastoreProvider();

    @Test
    void neitherAUriNorClusterNodesIsRefused() {
        assertThatThrownBy(() -> provider.create(Map.<String, String>of()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one of")
                .hasMessageContaining("modular.store.redis.cluster-nodes");
    }

    @Test
    void bothAUriAndClusterNodesIsRefused() {
        var properties = Map.of(
                "modular.store.redis.uri", "redis://localhost:6379",
                "modular.store.redis.cluster-nodes", "redis://localhost:7000");

        assertThatThrownBy(() -> provider.create(properties::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one of");
    }
}
