package digital.demilich.henge.examples.shop.orders;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.examples.shop.inventory.LineItem;
import java.time.Instant;
import java.util.UUID;

public record Order(UUID id, String customer, ImmutableList<LineItem> items, OrderStatus status, Instant placedAt) {

    public Order withStatus(OrderStatus status) {
        return new Order(id, customer, items, status, placedAt);
    }
}
