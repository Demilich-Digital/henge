package digital.demilich.henge.examples.shop.app;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.examples.shop.app.ShopTestSupport.Response;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Shop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The same jar as two processes, told apart by flags alone, with no shared store: one hosts inventory,
 * the other orders and notifications, and finds inventory at a configured url (which is what an
 * orchestrator's DNS would give it).
 */
class SplitTest {

    static Shop inventory;
    static Shop storefront;

    @BeforeAll
    static void start() {
        inventory = Shop.start("--henge.serve=inventory-service", "--shop.inventory.jdbc-url=jdbc:h2:mem:split;DB_CLOSE_DELAY=-1");
        storefront = Shop.start("--henge.serve=order-service,notification-service",
                "--henge.services.inventory-service.url=" + inventory.url());
    }

    @AfterAll
    static void stop() {
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
}
