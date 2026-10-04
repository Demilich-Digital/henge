package digital.demilich.henge.examples.shop.orders;

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
}
