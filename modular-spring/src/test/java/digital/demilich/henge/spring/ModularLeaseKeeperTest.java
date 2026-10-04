package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.ResourceProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ModularLeaseKeeperTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);
    private final ModularLeaseKeeper keeper = new ModularLeaseKeeper(store, Duration.ofSeconds(30), null);

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
        // Another node holds most of 'db'; a member of the same store under a different name stands in for it.
        store.claim("lease:db", "other-node", 8, 10, Duration.ofSeconds(30));

        LeaseNeed refused = keeper.acquireAll("b@1", List.of(new LeaseNeed("cache", 5, 10), new LeaseNeed("db", 5, 10)));

        assertThat(refused).isNotNull();
        assertThat(refused.name()).isEqualTo("db");
        // 'cache' was claimed first (names are claimed in order) and handed back.
        assertThat(store.read("lease:cache").members()).isEmpty();
    }

    @Test
    void servicesOnTheSameLeaseShareOneClaim() {
        var need = new LeaseNeed("db", 10, 10);

        assertThat(keeper.acquireAll("a@1", List.of(need))).isNull();
        assertThat(keeper.acquireAll("b@1", List.of(need))).isNull();

        var members = store.read("lease:db").members();
        assertThat(members).hasSize(1);
        assertThat(members.values()).extracting(java.util.Objects::requireNonNull)
                .allSatisfy(value -> assertThat(digital.demilich.henge.core.SystemEphemeralDatastore.claimedAmount(value)).isEqualTo(10));
    }

    @Test
    void aSharedClaimIsHandedBackWhenItsLastHolderLetsGo() {
        var need = new LeaseNeed("db", 10, 10);
        keeper.acquireAll("a@1", List.of(need));
        keeper.acquireAll("b@1", List.of(need));

        keeper.release("a@1");
        assertThat(store.read("lease:db").members()).hasSize(1);

        keeper.release("b@1");
        assertThat(store.read("lease:db").members()).isEmpty();
    }

    @Test
    void aRefusedServiceLeavesWhatItJoinedToItsOtherHolders() {
        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 10, 10)));
        store.claim("lease:cache", "other-node", 10, 10, Duration.ofSeconds(30));

        // c joins the 'db' a already holds, then is refused 'cache'.
        LeaseNeed refused = keeper.acquireAll("c@1", List.of(new LeaseNeed("db", 10, 10), new LeaseNeed("cache", 5, 10)));

        assertThat(refused.name()).isEqualTo("cache");
        assertThat(store.read("lease:db").members()).hasSize(1);

        // Letting go of a@1 now frees it: c never held it.
        keeper.release("a@1");
        assertThat(store.read("lease:db").members()).isEmpty();
    }

    @Test
    void releaseFreesTheCapacity() {
        keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 10, 10)));

        keeper.release("a@1");

        assertThat(store.read("lease:db").members()).isEmpty();
    }

    /** Opens an {@code Object} sized from the lease, counting what it opened and closed. */
    private static final class CountingProvider implements ResourceProvider<Object> {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        int lastAmount;

        @Override
        public Object open(Lease lease) {
            opened.incrementAndGet();
            lastAmount = lease.amount();
            return new Object();
        }

        @Override
        public void close(Object resource) {
            closed.incrementAndGet();
        }
    }

    private void providerFor(String lease, ResourceProvider<?> provider) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton(ModularLeaseKeeper.providerBeanName(lease), provider);
        keeper.setBeanFactory(beans);
    }

    @Test
    void aResourceIsOpenedOnceSharedAndClosedWhenItsLastHolderLetsGo() {
        var provider = new CountingProvider();
        providerFor("db", provider);
        var need = new LeaseNeed("db", 7, 10);

        keeper.acquireAll("a@1", List.of(need));
        keeper.acquireAll("b@1", List.of(need));

        assertThat(provider.opened).hasValue(1);
        assertThat(provider.lastAmount).isEqualTo(7);
        assertThat(keeper.resource("db")).isSameAs(keeper.resource("db"));

        keeper.release("a@1");
        assertThat(provider.closed).hasValue(0);
        keeper.release("b@1");
        assertThat(provider.closed).hasValue(1);
        assertThat(store.read("lease:db").members()).isEmpty();
    }

    @Test
    void aRefusedLeaseNeverOpensItsResource() {
        var provider = new CountingProvider();
        providerFor("db", provider);
        store.claim("lease:db", "other-node", 8, 10, Duration.ofSeconds(30));

        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNotNull();

        assertThat(provider.opened).hasValue(0);
    }

    @Test
    void aResourceThatFailsToOpenHandsBackEverythingClaimedAndOpened() {
        var good = new CountingProvider();
        var bad = new ResourceProvider<Object>() {
            @Override
            public Object open(Lease lease) {
                throw new IllegalStateException("database is down");
            }
        };
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton(ModularLeaseKeeper.providerBeanName("a-db"), good);
        beans.registerSingleton(ModularLeaseKeeper.providerBeanName("b-db"), bad);
        keeper.setBeanFactory(beans);

        assertThatThrownBy(() -> keeper.acquireAll("a@1", List.of(new LeaseNeed("a-db", 5, 10), new LeaseNeed("b-db", 5, 10))))
                .hasMessageContaining("provider of lease 'b-db' failed to open its resource")
                .hasRootCauseMessage("database is down");

        // 'a-db' was opened before 'b-db' failed, and is closed again along with both claims.
        assertThat(good.closed).hasValue(good.opened.get());
        assertThat(store.read("lease:a-db").members()).isEmpty();
        assertThat(store.read("lease:b-db").members()).isEmpty();
    }
}
