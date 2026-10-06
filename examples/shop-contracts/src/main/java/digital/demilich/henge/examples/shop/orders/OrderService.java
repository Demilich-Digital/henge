package digital.demilich.henge.examples.shop.orders;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.examples.shop.inventory.LineItem;
import digital.demilich.henge.examples.shop.inventory.OutOfStockException;
import java.util.UUID;

/** Takes orders, holding their stock and telling the customer. */
@HengeService
public interface OrderService {

    /**
     * Places an order: holds its stock, then tells the customer.
     *
     * @throws OutOfStockException if the stock isn't there; nothing is placed or held then
     */
    Order place(String customer, ImmutableList<LineItem> items);

    /** @throws OrderNotFoundException if there's no such order */
    Order get(UUID orderId);

    /**
     * Cancels an order, putting its stock back. Cancelling a cancelled order returns it unchanged.
     *
     * @throws OrderNotFoundException if there's no such order
     */
    Order cancel(UUID orderId);

    /**
     * Opens a feed of an order's status: its current status first, as text, then each change as it happens.
     * The channel is the client's, through whatever holds its connection, and this process never holds
     * that connection itself unless it is the one the client dialed.
     *
     * @throws OrderNotFoundException if there's no such order; the channel is refused then
     */
    ChannelHandler watch(UUID orderId, Channel toClient);
}
