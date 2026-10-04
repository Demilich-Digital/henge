package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.spring.leasedfixture.provided.FakePool;
import digital.demilich.henge.spring.leasedfixture.provided.PooledOneService;
import digital.demilich.henge.spring.leasedfixture.provided.PooledOneServiceImpl;
import digital.demilich.henge.spring.leasedfixture.provided.PooledTwoService;
import digital.demilich.henge.spring.leasedfixture.provided.PooledTwoServiceImpl;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class HengeRetirementTest {

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.leasedfixture.provided")
    static class PooledConfig {
    }

    private static AnnotationConfigApplicationContext started() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "henge.leases.pool-db.capacity", 100,
                "henge.leases.pool-db.amount", 40)));
        ctx.register(PooledConfig.class, HengeTransportConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    private static void retire(AnnotationConfigApplicationContext ctx, String name) throws InterruptedException {
        ctx.getBean(HengeServiceRegistry.class).retire(name, 1, Duration.ZERO, Duration.ofSeconds(5));
    }

    @Test
    void aRetiredServiceIsNoLongerHostedOrAdvertisedAndIsReachedRemotely() throws Exception {
        try (var ctx = started()) {
            var registry = ctx.getBean(HengeServiceRegistry.class);
            var store = ctx.getBean(SystemEphemeralDatastore.class);
            var one = ctx.getBean(PooledOneService.class);
            assertThat(store.read("adv:pooled-one-service@1").members()).hasSize(1);
            assertThat(one.pool()).endsWith(":40");

            retire(ctx, "pooled-one-service");

            assertThat(registry.find("pooled-one-service", 1)).isEmpty();
            assertThat(store.read("adv:pooled-one-service@1").members()).isEmpty();
            // The one injected before still works as a caller's handle, and is now a remote call.
            assertThatThrownBy(one::pool).hasMessageContaining("no process advertises it");
            // The sibling is untouched.
            assertThat(registry.find("pooled-two-service", 1)).isPresent();
            assertThat(store.read("adv:pooled-two-service@1").members()).hasSize(1);
            assertThat(ctx.getBean(PooledTwoService.class).pool()).endsWith(":40");
        }
    }

    @Test
    void theImplementationBeanIsDestroyed() throws Exception {
        try (var ctx = started()) {
            assertThat(ctx.getBeanNamesForType(PooledOneServiceImpl.class)).hasSize(1);

            retire(ctx, "pooled-one-service");

            assertThat(ctx.getBeanNamesForType(PooledOneServiceImpl.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(PooledTwoServiceImpl.class)).hasSize(1);
        }
    }

    @Test
    void theSharedResourceAndClaimOutliveAllButTheLastService() throws Exception {
        try (var ctx = started()) {
            var store = ctx.getBean(SystemEphemeralDatastore.class);
            int closedBefore = FakePool.CLOSED.get();

            retire(ctx, "pooled-one-service");

            // The other service still stands on the pool, and the node still holds its one claim.
            assertThat(FakePool.CLOSED.get()).isEqualTo(closedBefore);
            assertThat(store.read("lease:pool-db").members()).hasSize(1);

            retire(ctx, "pooled-two-service");

            assertThat(FakePool.CLOSED.get()).isEqualTo(closedBefore + 1);
            assertThat(store.read("lease:pool-db").members()).isEmpty();
        }
    }

    @Test
    void retiringTwiceOrWhatIsntHostedIsHarmless() throws Exception {
        try (var ctx = started()) {
            retire(ctx, "pooled-one-service");
            retire(ctx, "pooled-one-service");

            assertThatThrownBy(() -> retire(ctx, "nobody-service")).isInstanceOf(IllegalArgumentException.class);
            assertThat(ctx.getBean(HengeServiceRegistry.class).find("pooled-two-service", 1)).isPresent();
        }
    }

    @Test
    void closingTheContextAfterRetirementDoesNotReleaseTwice() throws Exception {
        SystemEphemeralDatastore store;
        int closedBefore = FakePool.CLOSED.get();
        try (var ctx = started()) {
            store = ctx.getBean(SystemEphemeralDatastore.class);
            retire(ctx, "pooled-one-service");
        }

        assertThat(store.read("lease:pool-db").members()).isEmpty();
        assertThat(FakePool.CLOSED.get()).isEqualTo(closedBefore + 1);
    }
}
