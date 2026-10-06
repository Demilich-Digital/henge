package digital.demilich.henge.spring.fixture.feed;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.ServiceVersion;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@ServiceVersion(value = FeedService.class, version = 1)
public class FeedServiceImpl implements FeedService {

    /** What the handlers of every channel opened so far have been told, in order. */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    /** The channel of the latest open, so a test can close it from the service's side. */
    public static final AtomicReference<Channel> LAST_CHANNEL = new AtomicReference<>();

    @Override
    public ChannelHandler watch(String topic, Channel toClient) {
        if (topic.equals("missing")) {
            throw new FeedNotFoundException("no feed " + topic);
        }
        LAST_CHANNEL.set(toClient);
        toClient.sendText("watching " + topic);
        if (topic.equals("flood")) {
            // More than any queue holds, faster than a connection sends it.
            for (int i = 0; i < 20_000; i++) {
                toClient.sendText("frame " + i);
            }
        }
        return new ChannelHandler() {
            @Override
            public void onText(String text) {
                EVENTS.add("text:" + text);
                toClient.sendText("echo:" + text);
            }

            @Override
            public void onBinary(byte[] data) {
                EVENTS.add("binary:" + data.length);
                toClient.sendBinary(data);
            }

            @Override
            public void onClose(CloseStatus status) {
                EVENTS.add("close:" + status.code());
            }
        };
    }

    @Override
    public String name() {
        return "feed";
    }
}
