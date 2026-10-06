package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.spring.fixture.feed.FeedNotFoundException;
import digital.demilich.henge.spring.fixture.feed.FeedService;
import digital.demilich.henge.spring.fixture.feed.FeedServiceImpl;
import digital.demilich.henge.spring.fixture.feed.FeedTestConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** A channel method called in the same process: the call itself, a handler that comes straight back. */
class ChannelBindingTest {

    /** The client's end, as the service sees it. */
    private static final class RecordingChannel implements Channel {

        final List<String> sent = new ArrayList<>();
        CloseStatus closedWith;

        @Override
        public String id() {
            return "client-1";
        }

        @Override
        public boolean isOpen() {
            return closedWith == null;
        }

        @Override
        public void sendText(String text) {
            sent.add("text:" + text);
        }

        @Override
        public void sendBinary(byte[] data) {
            sent.add("binary:" + data.length);
        }

        @Override
        public void close(CloseStatus status) {
            closedWith = status;
        }
    }

    private AnnotationConfigApplicationContext context;
    private FeedService feed;

    @BeforeEach
    void setUp() {
        FeedServiceImpl.EVENTS.clear();
        FeedServiceImpl.LAST_CHANNEL.set(null);
        context = new AnnotationConfigApplicationContext(FeedTestConfig.class, HengeTransportConfiguration.class);
        feed = context.getBean(FeedService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void opensAChannelAndFramesFlowBothWays() {
        var client = new RecordingChannel();

        ChannelHandler handler = feed.watch("orders", client);
        handler.onText("hello");
        handler.onBinary(new byte[] {1, 2, 3});

        assertThat(client.sent).containsExactly("text:watching orders", "text:echo:hello", "binary:3");
        assertThat(FeedServiceImpl.EVENTS).containsExactly("text:hello", "binary:3");
    }

    @Test
    void theClientClosingRunsOnCloseOnceAndStopsFrames() {
        var client = new RecordingChannel();
        ChannelHandler handler = feed.watch("orders", client);

        handler.onClose(CloseStatus.NORMAL);
        handler.onClose(CloseStatus.NORMAL);
        handler.onText("late");

        assertThat(FeedServiceImpl.EVENTS).containsExactly("close:1000");
        // The client already knows, so it isn't told again.
        assertThat(client.closedWith).isNull();
        assertThat(FeedServiceImpl.LAST_CHANNEL.get().isOpen()).isFalse();
    }

    @Test
    void theServiceClosingTheChannelClosesTheClientAndRunsOnClose() {
        var client = new RecordingChannel();
        ChannelHandler handler = feed.watch("orders", client);

        FeedServiceImpl.LAST_CHANNEL.get().close(new CloseStatus(1000, "done"));
        handler.onClose(CloseStatus.NORMAL);

        assertThat(client.closedWith).isEqualTo(new CloseStatus(1000, "done"));
        assertThat(FeedServiceImpl.EVENTS).containsExactly("close:1000");
    }

    @Test
    void anOpenThatThrowsRefusesTheChannelWithTheServicesException() {
        var client = new RecordingChannel();

        assertThatThrownBy(() -> feed.watch("missing", client)).isInstanceOf(FeedNotFoundException.class);

        assertThat(client.sent).isEmpty();
        assertThat(FeedServiceImpl.EVENTS).isEmpty();
    }

    @Test
    void retiringTheServiceClosesOpenChannelsAsARestart() throws Exception {
        var first = new RecordingChannel();
        var second = new RecordingChannel();
        feed.watch("a", first);
        feed.watch("b", second);

        context.getBean(HengeServiceRegistry.class).retire("feed-service", 1, Duration.ZERO, Duration.ofSeconds(5));

        assertThat(first.closedWith.code()).isEqualTo(CloseStatus.SERVICE_RESTART);
        assertThat(second.closedWith.code()).isEqualTo(CloseStatus.SERVICE_RESTART);
        assertThat(FeedServiceImpl.EVENTS).containsExactly("close:1012", "close:1012");
    }

    @Test
    void anOpenChannelDoesNotHoldARetirementDrainOpen() throws Exception {
        feed.watch("a", new RecordingChannel());
        long start = System.nanoTime();

        context.getBean(HengeServiceRegistry.class).retire("feed-service", 1, Duration.ZERO, Duration.ofSeconds(30));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aChannelOpenedAfterRetirementIsRefusedRatherThanSentOverTheNetwork() throws Exception {
        context.getBean(HengeServiceRegistry.class).retire("feed-service", 1, Duration.ZERO, Duration.ofSeconds(5));

        assertThatThrownBy(() -> feed.watch("a", new RecordingChannel())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aChannelMethodIsNotCallableOverASingleRequest() {
        var registry = context.getBean(HengeServiceRegistry.class);
        var controller = new HengeDispatcherController(
                registry, HengeTransportSupport.objectMapper(), new HengeProperties(new MockEnvironment()),
                ServiceDispatchObserver.NONE);

        assertThatThrownBy(() -> controller.dispatch("feed-service", 1, "watch", null,
                new ByteArrayInputStream("[\"a\", null]".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(HengeDispatchException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(400));
        assertThat(FeedServiceImpl.LAST_CHANNEL.get()).isNull();
    }

    @Test
    void closeStatusesFollowTheDesignTable() {
        assertThat(ClientChannels.closeStatusFor(new FeedNotFoundException("x")).code()).isEqualTo(4404);
        assertThat(ClientChannels.closeStatusFor(new digital.demilich.henge.core.ServiceVersionUnsupportedException("x")).code())
                .isEqualTo(4501);
        assertThat(ClientChannels.closeStatusFor(new digital.demilich.henge.core.StoreUnavailableException("x")).code())
                .isEqualTo(1013);
        assertThat(ClientChannels.closeStatusFor(new IllegalStateException("x")).code()).isEqualTo(1011);
    }
}
