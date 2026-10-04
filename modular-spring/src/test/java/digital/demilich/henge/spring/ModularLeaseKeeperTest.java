package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModularLeaseKeeperTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);
    private final ModularLeaseKeeper keeper = new ModularLeaseKeeper(store, Duration.ofSeconds(30));

    @AfterEach
    void close() {
        keeper.destroy();
    }

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    @Test
    void heartbeatRenewalKeepsAHeldLeaseAliveBeyondItsTtl() {
        var need = new LeaseNeed("db", 10, 10);
        assertThat(keeper.acquireAll("a@1", List.of(need))).isNull();

        advance(Duration.ofSeconds(20));
        keeper.renewAll();
        advance(Duration.ofSeconds(20));

        assertThat(store.claim("lease:db", "b@1", 1, 10, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void withoutRenewalALeaseExpiresAndFreesItsCapacity() {
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 10, 10)))).isNull();

        advance(Duration.ofSeconds(31));

        assertThat(store.claim("lease:db", "b@1", 10, 10, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void severalLeasesAreAllOrNothing() {
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 8, 10)))).isNull();

        LeaseNeed refused = keeper.acquireAll("b@1", List.of(new LeaseNeed("cache", 5, 10), new LeaseNeed("db", 5, 10)));

        assertThat(refused).isNotNull();
        assertThat(refused.name()).isEqualTo("db");
        // 'cache' was claimed first (names are claimed in order) and handed back.
        assertThat(store.read("lease:cache").members()).isEmpty();
        assertThat(keeper.hosts("b@1")).isFalse();
        assertThat(keeper.hosts("a@1")).isTrue();
    }

    @Test
    void releaseFreesTheCapacityAndForgetsTheService() {
        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 10, 10)));

        keeper.release("a@1");

        assertThat(keeper.hosts("a@1")).isFalse();
        assertThat(store.read("lease:db").members()).isEmpty();
    }
}
