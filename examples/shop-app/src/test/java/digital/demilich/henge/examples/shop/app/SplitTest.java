package digital.demilich.henge.examples.shop.app;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.examples.shop.app.ShopTestSupport.Feed;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Response;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Shop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The same jar as two processes, told apart by flags alone, with no shared store: one hosts inventory,
 * the other orders and notifications, and finds inventory at a configured url (which is what an
 * orchestrator's DNS would give it). A third process hosts neither, and is where customers' websockets
 * end: it holds their connections, and opens one trunk to the storefront for all of them.
 */
class SplitTest {

    static Shop inventory;
    static Shop storefront;
    static Shop edge;

    @BeforeAll
    static void start() {
        inventory = Shop.start("--henge.store.type=in-process", "--henge.serve=inventory-service", "--shop.inventory.jdbc-url=jdbc:h2:mem:split;DB_CLOSE_DELAY=-1");
        storefront = Shop.start("--henge.store.type=in-process", "--henge.serve=order-service,notification-service",
                "--henge.services.inventory-service.url=" + inventory.url());
        edge = Shop.start("--henge.store.type=in-process", "--henge.serve=notification-service",
                "--henge.services.order-service.url=" + storefront.url(),
                "--henge.services.inventory-service.url=" + inventory.url());
    }

    @AfterAll
    static void stop() {
        edge.close();
        storefront.close();
        inventory.close();
    }

    @Test
    void anOrderPlacedOnTheStorefrontHoldsStockInTheInventoryProcess() {
        int before = inventory.available("rope");

        assertThat(storefront.placeOrder("ada", "rope", 2).status()).isEqualTo(201);

        assertThat(inventory.available("rope")).isEqualTo(before - 2);
        assertThat(storefront.available("rope")).isEqualTo(before - 2);
    }

    @Test
    void outOfStockCrossesTheProcessBoundaryAsItself() {
        // Inventory throws OutOfStockException in its own process; the storefront's handler catches it by
        // type, as it does when inventory is embedded.
        Response refused = storefront.placeOrder("bob", "chisel", 1000);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("detail").asText()).contains("chisel: 1000 wanted");
    }

    @Test
    void aCustomersWebsocketOnTheEdgeWatchesAnOrderHeldByTheStorefront() throws Exception {
        String id = storefront.placeOrder("dee", "rope", 1).body().get("id").asText();

        Feed feed = edge.watch(id);

        assertThat(feed.next()).isEqualTo("PLACED");
        storefront.post("/api/orders/" + id + "/cancel", null);
        assertThat(feed.next()).isEqualTo("CANCELLED");
    }

    @Test
    void anOrderTheStorefrontDoesNotHaveRefusesTheEdgesChannelAsNotFound() throws Exception {
        assertThat(edge.watch("00000000-0000-0000-0000-000000000000").closedWith()).isEqualTo(4404);
    }
}
