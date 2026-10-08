package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.Acquisition;
import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.RateLimit;
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

class HengeLeaseKeeperTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);
    private final HengeLeaseKeeper keeper = new HengeLeaseKeeper(store, Duration.ofSeconds(30), null);

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
    void aStoreThatFailsMidwayLeavesNothingClaimedAndTheNextTryStartsFromNothing() {
        // 'cache' is claimed first, then the store goes away on 'db'.
        var flaky = new HengeLeaseKeeper(new SystemEphemeralDatastoreFailingOn("lease:db", store), Duration.ofSeconds(30), null);
        var needs = List.of(new LeaseNeed("cache", 5, 10), new LeaseNeed("db", 5, 10));

        assertThatThrownBy(() -> flaky.acquireAll("a@1", needs)).isInstanceOf(StoreUnavailableException.class);

        assertThat(store.read("lease:cache").members()).isEmpty();
        // Asked again once the store is back (here, by the keeper over the working one), both are claimed.
        assertThat(keeper.acquireAll("a@1", needs)).isNull();
        assertThat(store.read("lease:cache").members()).hasSize(1);
        assertThat(store.read("lease:db").members()).hasSize(1);
    }

    /** Claims work, except on {@code failingKey}, where the store can't be reached. */
    private record SystemEphemeralDatastoreFailingOn(String failingKey, SystemEphemeralDatastore delegate)
            implements SystemEphemeralDatastore {

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            if (key.equals(failingKey)) {
                throw new StoreUnavailableException("down");
            }
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    /** A claim of {@code amount} as another node makes it. */
    private void foreignClaim(String member, int amount) {
        store.put("lease:db", member, java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(amount).array(), Duration.ofSeconds(30));
    }

    /** What the store does to this node's claim when it is wiped, and another node claims the capacity. */
    private void loseClaim(int takenByOthers) {
        store.remove("lease:db", HengeLeaseKeeper.MEMBER);
        foreignClaim("other", takenByOthers);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void aNodeWhoseClaimIsGoneAndWhoseCapacityIsTakenGivesTheLeaseUp() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        keeper.onEviction("a@1", evicted::incrementAndGet);
        loseClaim(10);

        keeper.renewAll();

        awaitTrue(() -> evicted.get() > 0);
        assertThat(evicted).hasValue(1);
    }

    @Test
    void aNodeWhoseClaimIsGoneButWhoseCapacityIsFreeSimplyHasItAgain() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        keeper.onEviction("a@1", evicted::incrementAndGet);
        loseClaim(5);

        keeper.renewAll();
        Thread.sleep(50);

        assertThat(evicted).hasValue(0);
        assertThat(store.read("lease:db").members()).hasSize(2);
    }

    @Test
    void aNodeThatSeesTheClusterOverItsOwnCapacityStandsDownEvenWithItsClaimHeld() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        keeper.onEviction("a@1", evicted::incrementAndGet);
        // Another node, rolled out with a larger capacity, claimed 10 beside this node's 5: to this node, which
        // believes in 10, the cluster is over, and it makes room for the nodes that don't.
        foreignClaim("other", 10);

        keeper.renewAll();

        awaitTrue(() -> evicted.get() > 0);
        assertThat(evicted).hasValue(1);
    }

    @Test
    void aLeaseLostBeforeItsEvictorIsRegisteredIsGivenUpOnRegistration() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        loseClaim(10);
        // Refused between the claim and the service being hosted: there is no evictor to run yet.
        keeper.renewAll();

        keeper.onEviction("a@1", evicted::incrementAndGet);

        awaitTrue(() -> evicted.get() > 0);
        Thread.sleep(50);
        assertThat(evicted).hasValue(1);
    }

    @Test
    void anEvictorRegisteredOnAHealthyLeaseDoesNotRun() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();

        keeper.onEviction("a@1", evicted::incrementAndGet);

        Thread.sleep(50);
        assertThat(evicted).hasValue(0);
    }

    /** Everything works, except taking a member out, while {@code down} is set. */
    private static final class FailingRemoves implements SystemEphemeralDatastore {
        private final SystemEphemeralDatastore delegate;
        volatile boolean down = true;

        FailingRemoves(SystemEphemeralDatastore delegate) {
            this.delegate = delegate;
        }

        @Override
        public String nodeId() {
            return delegate.nodeId();
        }

        @Override
        public void put(String key, String localName, byte[] value, Duration ttl) {
            delegate.put(key, localName, value, ttl);
        }

        @Override
        public void remove(String key, String localName) {
            if (down) {
                throw new StoreUnavailableException("down");
            }
            delegate.remove(key, localName);
        }

        @Override
        public Snapshot read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
            return delegate.tryAcquire(key, amount, limit);
        }
    }

    @Test
    void aHandBackTheStoreFailsStillLetsTheLeaseGoAndItLapses() {
        var flaky = new HengeLeaseKeeper(new FailingRemoves(store), Duration.ofSeconds(30), null);
        assertThat(flaky.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();

        flaky.release("a@1");

        assertThat(flaky.isHeld("db")).isFalse();
        // Not taken back, and not renewed either: it lapses with its TTL.
        assertThat(store.read("lease:db").members()).hasSize(1);
        advance(Duration.ofSeconds(31));
        assertThat(store.read("lease:db").members()).isEmpty();
        flaky.destroy();
    }

    @Test
    void aRefusalWhoseHandBackFailsKeepsNothingHeld() {
        var flaky = new HengeLeaseKeeper(new FailingRemoves(store), Duration.ofSeconds(30), null);
        store.claim("lease:db", "other-node", 8, 10, Duration.ofSeconds(30));

        // 'cache' and 'events' are claimed, then 'db' is refused, and handing the first two back fails.
        LeaseNeed refused = flaky.acquireAll("a@1",
                List.of(new LeaseNeed("cache", 5, 10), new LeaseNeed("db", 5, 10), new LeaseNeed("events", 1, 10)));

        assertThat(refused.name()).isEqualTo("db");
        assertThat(flaky.isHeld("cache")).isFalse();
        flaky.destroy();
    }

    @Test
    void aLeaseBeingGivenUpIsNotGivenUpAgainOnTheNextHeartbeat() throws Exception {
        var evicted = new AtomicInteger();
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        keeper.onEviction("a@1", evicted::incrementAndGet);
        loseClaim(10);

        keeper.renewAll();
        keeper.renewAll();
        keeper.renewAll();

        awaitTrue(() -> evicted.get() > 0);
        Thread.sleep(50);
        assertThat(evicted).hasValue(1);
    }

    private int memberAmount(String member) {
        var members = store.read("lease:db").members();
        var value = members.get(new SystemEphemeralDatastore.MemberId(store.nodeId(), member));
        return value == null ? Integer.MIN_VALUE : SystemEphemeralDatastore.claimedAmount(value);
    }

    @Test
    void aLeaseBeingGivenUpIsKeptAliveAsOneBeingGivenUpAndWrittenAgainIfTheStoreLostIt() throws Exception {
        var need = new LeaseNeed("db", 5, 10);
        assertThat(keeper.acquireAll("a@1", List.of(need))).isNull();
        keeper.onEviction("a@1", () -> { });
        loseClaim(10);

        keeper.renewAll();
        assertThat(memberAmount(HengeLeaseKeeper.MEMBER)).isEqualTo(-5);

        // The store is wiped while it is being given up: the next heartbeat says so again.
        store.remove("lease:db", HengeLeaseKeeper.MEMBER);
        keeper.renewAll();
        assertThat(memberAmount(HengeLeaseKeeper.MEMBER)).isEqualTo(-5);
    }

    @Test
    void aServiceCantJoinALeaseBeingGivenUp() throws Exception {
        var need = new LeaseNeed("db", 5, 10);
        assertThat(keeper.acquireAll("a@1", List.of(need))).isNull();
        keeper.onEviction("a@1", () -> { });
        loseClaim(10);
        keeper.renewAll();

        assertThat(keeper.acquireAll("b@1", List.of(need))).isEqualTo(need);
        assertThat(keeper.isHeld("db")).isFalse();
    }

    @Test
    void theNodesStayingAreNotRefusedOnceAnotherHasStartedGivingUp() throws Exception {
        var evicted = new AtomicInteger();
        // This node believes in a capacity of 10, and holds 5 of it, as it fitted.
        assertThat(keeper.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        keeper.onEviction("a@1", evicted::incrementAndGet);
        // Then nodes that believe in 15 claim 5 each, which fits theirs: the store holds 15, and this node is refused.
        assertThat(store.claim("lease:db", "n", 5, 15, Duration.ofSeconds(30))).isTrue();
        assertThat(store.claim("lease:db", "b", 5, 15, Duration.ofSeconds(30))).isTrue();

        keeper.renewAll();
        awaitTrue(() -> evicted.get() > 0);

        assertThat(evicted).hasValue(1);
        // Another node that believes in 10 renews its claim, and isn't asked to make room for the one that is going.
        assertThat(store.claim("lease:db", "b", 5, 10, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void everyServiceOnALostLeaseIsGivenUp() throws Exception {
        var evicted = new AtomicInteger();
        var need = new LeaseNeed("db", 5, 10);
        keeper.acquireAll("a@1", List.of(need));
        keeper.acquireAll("b@1", List.of(need));
        keeper.onEviction("a@1", evicted::incrementAndGet);
        keeper.onEviction("b@1", evicted::incrementAndGet);
        loseClaim(10);

        keeper.renewAll();

        awaitTrue(() -> evicted.get() == 2);
        assertThat(evicted).hasValue(2);
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
        beans.registerSingleton(HengeLeaseKeeper.providerBeanName(lease), provider);
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
        beans.registerSingleton(HengeLeaseKeeper.providerBeanName("a-db"), good);
        beans.registerSingleton(HengeLeaseKeeper.providerBeanName("b-db"), bad);
        keeper.setBeanFactory(beans);

        assertThatThrownBy(() -> keeper.acquireAll("a@1", List.of(new LeaseNeed("a-db", 5, 10), new LeaseNeed("b-db", 5, 10))))
                .hasMessageContaining("provider of lease 'b-db' failed to open its resource")
                .hasRootCauseMessage("database is down");

        // 'a-db' was opened before 'b-db' failed, and is closed again along with both claims.
        assertThat(good.closed).hasValue(good.opened.get());
        assertThat(store.read("lease:a-db").members()).isEmpty();
        assertThat(store.read("lease:b-db").members()).isEmpty();
    }

    @Test
    void aClaimTheStoreLostIsWrittenAgainAsSoonAsTheStoreIsBack() throws InterruptedException {
        var down = new java.util.concurrent.atomic.AtomicBoolean();
        SystemEphemeralDatastore toggling = (SystemEphemeralDatastore) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {SystemEphemeralDatastore.class}, (proxy, method, args) -> {
                    if (down.get() && !method.getName().equals("nodeId")) {
                        throw new StoreUnavailableException("down");
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var guard = new GuardedDatastore(toggling, Duration.ofMillis(1), Duration.ofMillis(1), now::get);
        var guarded = new HengeLeaseKeeper(guard, Duration.ofSeconds(30), null);
        assertThat(guarded.acquireAll("a@1", List.of(new LeaseNeed("db", 5, 10)))).isNull();
        down.set(true);
        assertThatThrownBy(() -> guard.read("lease:db")).isInstanceOf(StoreUnavailableException.class);
        down.set(false);
        store.remove("lease:db", HengeLeaseKeeper.MEMBER); // the store came back empty
        advance(Duration.ofMillis(1));

        guard.read("lease:db"); // the call that finds it back; this is also the first look at the loss

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (store.read("lease:db").members().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(store.read("lease:db").members()).hasSize(1);
        guarded.destroy();
    }
}
