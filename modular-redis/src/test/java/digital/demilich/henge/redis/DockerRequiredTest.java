package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * The Redis-backed tests skip themselves where there's no Docker, which is right on a laptop and
 * wrong in CI, where a skip is a green build that tested nothing. The build sets
 * {@code henge.requireDocker} when {@code CI} is set; this fails then, instead of skipping.
 */
class DockerRequiredTest {

    @Test
    void dockerIsAvailableWhereTheBuildRequiresIt() {
        if (!Boolean.getBoolean("henge.requireDocker")) {
            return;
        }
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required (henge.requireDocker) for the Redis-backed tests")
                .isTrue();
    }
}
