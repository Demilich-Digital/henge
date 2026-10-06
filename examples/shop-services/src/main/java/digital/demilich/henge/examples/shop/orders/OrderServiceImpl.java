package digital.demilich.henge.examples.shop.orders;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.examples.shop.inventory.InventoryService;
import digital.demilich.henge.examples.shop.inventory.LineItem;
import digital.demilich.henge.examples.shop.notifications.NotificationService;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders live in this object's memory, which keeps the example short and is exactly the state that
 * doesn't survive being spread out: two processes hosting this service would each know only their own
 * orders. Host it in one process, or give it a database the way inventory has one.
 *
 * <p>It calls inventory and notifications through their interfaces, and neither knows nor cares whether
 * they run in this process or another.
 */
@ServiceVersion(value = OrderService.class, version = 1)
public class OrderServiceImpl implements OrderService {

    private final InventoryService inventory;
    private final NotificationService notifications;
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
    /** Who is watching each order: the same kind of state as the orders, held here for the same reason. */
    private final Map<UUID, Set<Channel>> watchers = new ConcurrentHashMap<>();

    public OrderServiceImpl(InventoryService inventory, NotificationService notifications) {
        this.inventory = inventory;
        this.notifications = notifications;
    }

    @Override
    public Order place(String customer, ImmutableList<LineItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item");
        }
        UUID id = UUID.randomUUID();
        inventory.reserve(id, items);
        Order order = new Order(id, customer, items, OrderStatus.PLACED, Instant.now());
        orders.put(id, order);
        // A throttled notification doesn't fail the order.
        notifications.notify(customer, "Order " + id + " placed");
        return order;
    }

    @Override
    public Order get(UUID orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException("No order " + orderId);
        }
        return order;
    }

    @Override
    public Order cancel(UUID orderId) {
        Order order = get(orderId);
        if (order.status() == OrderStatus.CANCELLED) {
            return order;
        }
        inventory.release(orderId);
        Order cancelled = order.withStatus(OrderStatus.CANCELLED);
        orders.put(orderId, cancelled);
        notifications.notify(order.customer(), "Order " + orderId + " cancelled");
        watchers.getOrDefault(orderId, Set.of()).forEach(watcher -> watcher.sendText(cancelled.status().name()));
        return cancelled;
    }

    @Override
    public ChannelHandler watch(UUID orderId, Channel toClient) {
        get(orderId); // refuses the channel for an order that isn't there
        Set<Channel> forOrder = watchers.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet());
        forOrder.add(toClient);
        // Added before the status is read, so a cancellation in between is sent twice rather than missed.
        toClient.sendText(orders.get(orderId).status().name());
        return new ChannelHandler() {
            @Override
            public void onClose(CloseStatus status) {
                forOrder.remove(toClient);
            }
        };
    }
}
