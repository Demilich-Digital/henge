package digital.demilich.henge.spring.fixture.feed;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.HengeService;

@HengeService(name = "feed-service")
public interface FeedService {

    /** Greets the client on the channel, and echoes what it sends. */
    ChannelHandler watch(String topic, Channel toClient);

    String name();
}
