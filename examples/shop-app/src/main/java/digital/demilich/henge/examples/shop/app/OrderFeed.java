package digital.demilich.henge.examples.shop.app;

import digital.demilich.henge.examples.shop.orders.OrderService;
import digital.demilich.henge.spring.ClientChannels;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The websocket a customer watches an order on, {@code /ws/orders/{id}}: plain Spring websocket, owned by
 * the application, with one line of Henge in it. Whether the orders service is in this process or another
 * is not visible here: the client's connection stays with this process, which holds thousands of them,
 * while the orders process holds one connection per frontend however many customers are watching.
 *
 * <p>Authenticating the customer, and checking that the order is theirs, would be this endpoint's job.
 */
@Configuration
@EnableWebSocket
class OrderFeed implements WebSocketConfigurer {

    private final OrderService orders;

    OrderFeed(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(ClientChannels.bridge((session, toClient) -> orders.watch(orderId(session), toClient)),
                "/ws/orders/*");
    }

    private static UUID orderId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }
}
