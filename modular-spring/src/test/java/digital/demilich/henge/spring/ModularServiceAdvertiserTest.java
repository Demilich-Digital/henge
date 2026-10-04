package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import digital.demilich.henge.spring.fixture.counter.CounterService;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV1;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV2;
import digital.demilich.henge.spring.fixture.counter.CounterTestConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ModularServiceAdvertiserTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final InProcessEphemeralDatastore store = new InProcessEphemeralDatastore(now::get);
    private final GenericApplicationContext hosting = hostingBothVersions();
    private final ModularServiceRegistry registry = registryOver(hosting);
    private final ModularServiceAdvertiser advertiser =
            new ModularServiceAdvertiser(store, registry, "http://10.0.0.7:8080", Duration.ofSeconds(30));

    /** A context in which this process embeds both versions of the counter service. */
    private static GenericApplicationContext hostingBothVersions() {
        var ctx = new GenericApplicationContext();
        ctx.registerBean("counter-service-1", ModularServiceBindingFactoryBean.class, () -> new ModularServiceBindingFactoryBean(
                ServiceBindingSpec.embedded(CounterService.class, "counter-service", 1, CounterServiceV1.class)));
        ctx.registerBean("counter-service-2", ModularServiceBindingFactoryBean.class, () -> new ModularServiceBindingFactoryBean(
                ServiceBindingSpec.embedded(CounterService.class, "counter-service", 2, CounterServiceV2.class)));
        ctx.refresh();
        return ctx;
    }

    private static ModularServiceRegistry registryOver(GenericApplicationContext ctx) {
        var registry = new ModularServiceRegistry(List.of(
                ModularServiceDescriptor.of("counter-service", 1, CounterService.class, "counter-service-1"),
                ModularServiceDescriptor.of("counter-service", 2, CounterService.class, "counter-service-2")));
        registry.setBeanFactory(ctx);
        return registry;
    }

    @AfterEach
    void stop() {
        advertiser.stop();
        hosting.close();
    }

    private void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    private ServiceAdvertisement advertisementOf(String service, int version) {
        Map<MemberId, byte[]> members = store.read(ServiceAdvertisement.key(service, version)).members();
        assertThat(members).containsOnlyKeys(new MemberId(store.nodeId(), ModularServiceAdvertiser.MEMBER));
        return ServiceAdvertisement.decode(members.values().iterator().next());
    }

    @Test
    void startAdvertisesEveryHostedServiceVersionWithTheAdvertiseUrl() {
        advertiser.start();

        assertThat(advertisementOf("counter-service", 1).url()).isEqualTo("http://10.0.0.7:8080");
        assertThat(advertisementOf("counter-service", 2).url()).isEqualTo("http://10.0.0.7:8080");
        assertThat(advertiser.isRunning()).isTrue();
    }

    @Test
    void theHeartbeatKeepsAdvertisementsAliveBeyondTheirTtl() {
        advertiser.start();

        advance(Duration.ofSeconds(20));
        advertiser.renew();
        advance(Duration.ofSeconds(20));

        assertThat(store.read(ServiceAdvertisement.key("counter-service", 1)).members()).hasSize(1);
    }

    @Test
    void aProcessThatStopsHeartbeatingDisappearsAfterTheTtl() {
        advertiser.start();

        advance(Duration.ofSeconds(31));

        assertThat(store.read(ServiceAdvertisement.key("counter-service", 1)).members()).isEmpty();
    }

    @Test
    void renewingRestoresAdvertisementsTheStoreLost() {
        advertiser.start();
        store.remove(ServiceAdvertisement.key("counter-service", 1), ModularServiceAdvertiser.MEMBER);

        advertiser.renew();

        assertThat(store.read(ServiceAdvertisement.key("counter-service", 1)).members()).hasSize(1);
    }

    @Test
    void stopWithdrawsEveryAdvertisement() {
        advertiser.start();

        advertiser.stop();

        assertThat(store.read(ServiceAdvertisement.key("counter-service", 1)).members()).isEmpty();
        assertThat(store.read(ServiceAdvertisement.key("counter-service", 2)).members()).isEmpty();
        assertThat(advertiser.isRunning()).isFalse();
    }

    @Test
    void aProcessHostingNothingAdvertisesNothing() {
        var empty = new ModularServiceAdvertiser(store, new ModularServiceRegistry(List.of()), null, Duration.ofSeconds(30));

        empty.start();

        assertThat(empty.isRunning()).isTrue();
        assertThat(store.read(ServiceAdvertisement.key("counter-service", 1)).members()).isEmpty();
        empty.stop();
    }

    @Test
    void withoutAnAdvertiseUrlTheAdvertisementSaysNoUrl() {
        var noUrl = new ModularServiceAdvertiser(store, registry, null, Duration.ofSeconds(30));

        noUrl.start();

        assertThat(advertisementOf("counter-service", 1).url()).isNull();
        noUrl.stop();
    }

    @Test
    void anAdvertisementReadsBackAndIgnoresFieldsItDoesntKnow() {
        assertThat(ServiceAdvertisement.decode("{\"url\":\"http://a:1\",\"load\":0.5}".getBytes()).url()).isEqualTo("http://a:1");
    }

    @Test
    void aContextAdvertisesWhatItHostsOnceStartedAndWithdrawsOnClose() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.advertise.url", "http://pod-1:8080/")));
        ctx.register(CounterTestConfig.class);
        ctx.refresh();
        SystemEphemeralDatastore datastore = ctx.getBean(SystemEphemeralDatastore.class);

        assertThat(datastore.read("adv:counter-service@1").members()).hasSize(1);
        assertThat(ServiceAdvertisement.decode(datastore.read("adv:counter-service@2").members().values().iterator().next()).url())
                .isEqualTo("http://pod-1:8080");

        ctx.close();
        assertThat(datastore.read("adv:counter-service@1").members()).isEmpty();
    }

    @Test
    void aServiceThatIsRemoteHereIsNotAdvertisedByThisProcess() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "modular.serve", "counter-service@1", "modular.services.counter-service.url", "http://elsewhere:8080")));
        ctx.register(CounterTestConfig.class, ModularTransportConfiguration.class);
        ctx.refresh();
        try {
            SystemEphemeralDatastore datastore = ctx.getBean(SystemEphemeralDatastore.class);
            assertThat(datastore.read("adv:counter-service@1").members()).hasSize(1);
            assertThat(datastore.read("adv:counter-service@2").members()).isEmpty();
        } finally {
            ctx.close();
        }
    }

    @Test
    void aMalformedAdvertiseUrlFailsStartup() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.advertise.url", "pod-1:8080")));
        ctx.register(CounterTestConfig.class);

        assertThatThrownBy(ctx::refresh).hasStackTraceContaining("modular.advertise.url 'pod-1:8080' must be an absolute http");
        ctx.close();
    }
}
