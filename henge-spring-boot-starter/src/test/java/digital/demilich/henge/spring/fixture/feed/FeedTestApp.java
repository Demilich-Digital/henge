package digital.demilich.henge.spring.fixture.feed;

import digital.demilich.henge.spring.ClientChannels;
import digital.demilich.henge.spring.EnableHengeServices;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@SpringBootApplication
@EnableHengeServices
public class FeedTestApp {

    /** What an application writes: a websocket endpoint that opens a channel on a service. */
    @Configuration
    @EnableWebSocket
    static class Sockets implements WebSocketConfigurer {

        private final FeedService feed;

        Sockets(FeedService feed) {
            this.feed = feed;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(ClientChannels.bridge((session, toClient) ->
                    feed.watch(session.getUri().getQuery(), toClient)), "/ws/feed");
        }
    }
}
