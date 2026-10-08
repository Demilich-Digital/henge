package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A member whose TTL has passed, but which Redis hasn't reclaimed yet (active expiry is switched off to
 * hold it there), on the oldest Redis the store supports and the newest tested. {@code sample} promises
 * live members only, and {@code count} may include the lapsed ones.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisLapsedFieldsTest {

    @ParameterizedTest
    @ValueSource(strings = {"redis:7.4", "redis:8"})
    void sampleNeverReturnsALapsedMemberThoughCountMayStillIncludeIt(String image) throws Exception {
        try (GenericContainer<?> redis = new GenericContainer<>(image).withExposedPorts(6379)
                .withCommand("redis-server", "--enable-debug-command", "yes")) {
            redis.start();
            String uri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
            try (RedisEphemeralDatastore store = RedisEphemeralDatastore.connect(uri)) {
                redis.execInContainer("redis-cli", "DEBUG", "SET-ACTIVE-EXPIRE", "0");
                store.put("adv:k", "gone", new byte[] {1}, Duration.ofMillis(50));
                store.put("adv:k", "live", new byte[] {2}, Duration.ofHours(1));
                Thread.sleep(300);

                var sample = store.sample("adv:k", 10);

                assertThat(sample.members()).hasSize(1);
                assertThat(sample.live()).isBetween(1, 2);
            }
        }
    }
}
