package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastoreProvider;
import digital.demilich.henge.redis.RedisEphemeralDatastore;
import digital.demilich.henge.spring.leasedfixture.ledger.LedgerService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code modular.store.type} and the connection settings under {@code modular.store.<type>.*}. */
@Testcontainers(disabledWithoutDocker = true)
class ModularStoreSelectionTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    /** Registered with ServiceLoader in this module's test resources. */
    public static class FakeDatastoreProvider implements SystemEphemeralDatastoreProvider {
        static final AtomicReference<String> SEEN = new AtomicReference<>();
        static final AtomicReference<FakeDatastore> CREATED = new AtomicReference<>();

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public SystemEphemeralDatastore create(UnaryOperator<String> property) {
            SEEN.set(property.apply("modular.store.fake.name"));
            FakeDatastore store = new FakeDatastore();
            CREATED.set(store);
            return store;
        }
    }

    static class FakeDatastore implements SystemEphemeralDatastore, AutoCloseable {
        private final InProcessEphemeralDatastore delegate = new InProcessEphemeralDatastore();
        volatile boolean closed;

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
            return delegate.claim(key, localName, amount, capacity, ttl);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.leasedfixture.ledger")
    static class LeasedConfig {
    }

    @Configuration
    static class OwnStoreConfig {
        @Bean
        SystemEphemeralDatastore mine() {
            return new InProcessEphemeralDatastore();
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties, Class<?>... configs) {
        var ctx = new AnnotationConfigApplicationContext();
        Map<String, Object> all = new HashMap<>(Map.of(
                "modular.leases.ledger-db.capacity", 100,
                "modular.services.ledger-service.leases.ledger-db", 40,
                "modular.services.report-service.leases.ledger-db", 40));
        all.putAll(properties);
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", all));
        ctx.register(configs);
        return ctx;
    }

    @Test
    void theInProcessStoreIsTheDefaultAndCanBeNamedExplicitly() {
        try (var ctx = context(Map.of(), LeasedConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(SystemEphemeralDatastore.class)).isInstanceOf(InProcessEphemeralDatastore.class);
        }
        try (var ctx = context(Map.of("modular.store.type", "in-process"), LeasedConfig.class)) {
            ctx.refresh();
            assertThat(ctx.getBean(SystemEphemeralDatastore.class)).isInstanceOf(InProcessEphemeralDatastore.class);
        }
    }

    @Test
    void aProviderOnTheClasspathIsChosenByTypeAndGivenItsSettingsAndClosedWithTheContext() {
        FakeDatastore created;
        try (var ctx = context(Map.of("modular.store.type", "fake", "modular.store.fake.name", "node-a"), LeasedConfig.class)) {
            ctx.refresh();

            assertThat(FakeDatastoreProvider.SEEN.get()).isEqualTo("node-a");
            assertThat(ctx.getBean(SystemEphemeralDatastore.class)).isSameAs(FakeDatastoreProvider.CREATED.get());
            created = FakeDatastoreProvider.CREATED.get();
            // The chosen store really is the one in use.
            assertThat(created.read("lease:ledger-db").members()).hasSize(2);
            assertThat(created.closed).isFalse();
        }
        assertThat(created.closed).isTrue();
    }

    @Test
    void anUnknownTypeFailsStartupListingWhatIsAvailable() {
        try (var ctx = context(Map.of("modular.store.type", "nope"), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("modular.store.type=nope matches no datastore on the classpath")
                    .hasStackTraceContaining("available: in-process, fake, redis");
        }
    }

    @Test
    void aTypeAndADatastoreBeanTogetherFailStartup() {
        try (var ctx = context(Map.of("modular.store.type", "fake"), LeasedConfig.class, OwnStoreConfig.class)) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("modular.store.type=fake is set, but the application also defines a SystemEphemeralDatastore bean");
        }
    }

    @Test
    void redisWithoutAUriFailsStartupNamingTheProperty() {
        try (var ctx = context(Map.of("modular.store.type", "redis"), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("needs modular.store.redis.uri");
        }
    }

    @Test
    void redisThatCantBeReachedFailsStartupNamingTheAddress() {
        try (var ctx = context(Map.of("modular.store.type", "redis", "modular.store.redis.uri", "redis://localhost:1/?timeout=1s"), LeasedConfig.class)) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("Can't connect to Redis at redis://localhost:1");
        }
    }

    @Test
    void aContextOnRedisKeepsItsLeasesAndAdvertisementsThereWhereAnotherNodeSeesThem() {
        String uri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        try (var observer = RedisEphemeralDatastore.connect(uri)) {
            try (var ctx = context(Map.of("modular.store.type", "redis", "modular.store.redis.uri", uri,
                    "modular.advertise.url", "http://pod-1:8080"), LeasedConfig.class)) {
                ctx.refresh();

                assertThat(ctx.getBean(LedgerService.class).grant()).isEqualTo("ledger-db:40");
                assertThat(ctx.getBean(SystemEphemeralDatastore.class)).isInstanceOf(RedisEphemeralDatastore.class);

                var leases = observer.read("lease:ledger-db").members();
                assertThat(leases).hasSize(2);
                assertThat(leases.keySet()).allSatisfy(member -> assertThat(member.nodeId())
                        .isEqualTo(ctx.getBean(SystemEphemeralDatastore.class).nodeId()));
                // This node's claims count against another node's: 40 + 40 held, so 30 more is refused.
                assertThat(observer.claim("lease:ledger-db", "elsewhere", 30, 100, Duration.ofSeconds(30))).isFalse();
                assertThat(observer.claim("lease:ledger-db", "elsewhere", 20, 100, Duration.ofSeconds(30))).isTrue();
                observer.remove("lease:ledger-db", "elsewhere");

                var advertisement = observer.read("adv:ledger-service@1").members().values().iterator().next();
                assertThat(ServiceAdvertisement.decode(advertisement).url()).isEqualTo("http://pod-1:8080");
            }

            // Closing the context withdrew everything it had put there.
            assertThat(observer.read("lease:ledger-db").members()).isEmpty();
            assertThat(observer.read("adv:ledger-service@1").members()).isEmpty();
        }
    }
}
