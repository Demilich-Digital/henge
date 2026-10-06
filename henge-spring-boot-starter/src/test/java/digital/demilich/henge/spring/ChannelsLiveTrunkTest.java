package digital.demilich.henge.spring;

import static digital.demilich.henge.spring.ChannelTestSupport.freePort;
import static digital.demilich.henge.spring.ChannelTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.ServiceInvocation;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.spring.ChannelTestSupport.SwitchableStore;
import digital.demilich.henge.spring.fixture.feed.FeedService;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * A frontend that can't reach the store to ask where a service is, because what it knew was let go with
 * time, still opens a channel on a backend it already has a trunk to and has opened that service on: it
 * can't tell that backend from one that stopped advertising, but one it is talking to is there.
 */
class ChannelsLiveTrunkTest {

    private static final Duration REFRESH = Duration.ofSeconds(10);

    private static final class Frames implements Channel {

        final BlockingQueue<String> text = new LinkedBlockingQueue<>();

        @Override
        public String id() {
            return "frames";
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void sendText(String message) {
            text.add(message);
        }

        @Override
        public void sendBinary(byte[] data) {
        }

        @Override
        public void close(CloseStatus status) {
            text.add("closed:" + status.code());
        }
    }

    private final SwitchableStore store = new SwitchableStore();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    private final AdvertisedEndpoints endpoints = new AdvertisedEndpoints(store, REFRESH, now::get, SystemMetrics.NONE);

    private InternalRestTransport frontend() {
        var properties = new HengeProperties(new MockEnvironment());
        return new InternalRestTransport(
                HengeTransportSupport.restClient(properties, ObservationRegistry.NOOP), HengeTransportSupport.objectMapper(),
                properties, endpoints);
    }

    private static ServiceInvocation watch(String topic, Channel frames) throws Exception {
        return new ServiceInvocation("feed-service", 1, "watch",
                FeedService.class.getMethod("watch", String.class, Channel.class), new Object[] {topic, frames});
    }

    @Test
    void aTrunkThatServedTheServiceIsUsedWhenTheRouteIsGoneAndTheStoreIsAway() throws Exception {
        int port = freePort();
        try (ConfigurableApplicationContext backend = start(store, "server.port=" + port, "henge.advertise.url=http://localhost:" + port);
                InternalRestTransport transport = frontend()) {
            var first = new Frames();
            transport.open(watch("orders", first), first);
            assertThat(first.text.poll(10, TimeUnit.SECONDS)).isEqualTo("watching orders");

            // Time passes, and the store is reached about something else: the route is let go. Then it is away.
            now.updateAndGet(t -> t.plus(REFRESH.plusSeconds(1)));
            assertThat(endpoints.next("unrelated", 1)).isNull();
            store.down = true;

            var second = new Frames();
            transport.open(watch("more", second), second);
            assertThat(second.text.poll(10, TimeUnit.SECONDS)).isEqualTo("watching more");
        }
    }

    @Test
    void withNoTrunkToFallBackOnAnOpenFailsAsTheStoreBeingAway() throws Exception {
        store.down = true;
        try (InternalRestTransport transport = frontend()) {
            var frames = new Frames();

            assertThatThrownBy(() -> transport.open(watch("orders", frames), frames)).isInstanceOf(StoreUnavailableException.class);
        }
    }
}
