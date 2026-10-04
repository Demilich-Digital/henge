package digital.demilich.henge.examples.shop.inventory;

import digital.demilich.henge.core.AddedIn;
import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ImmutableMap;
import digital.demilich.henge.core.ImmutableSet;
import java.util.UUID;

/** Stock on hand, and the stock held for orders. */
@HengeService
public interface InventoryService {

    /**
     * Holds stock for every item of an order, or for none of them.
     *
     * @throws OutOfStockException if any item has less available than the order asks for
     */
    void reserve(UUID orderId, ImmutableList<LineItem> items);

    /** Puts an order's held stock back. An order with nothing held, or released already, is a no-op. */
    void release(UUID orderId);

    /** How many of {@code sku} can still be reserved; {@code 0} for a sku this shop doesn't stock. */
    int available(String sku);

    /** Adds {@code quantity} to what's available, stocking the sku if it's new. */
    void restock(String sku, int quantity);

    /** {@link #available} for several skus in one call. */
    @AddedIn(2)
    ImmutableMap<String, Integer> availability(ImmutableSet<String> skus);
}
