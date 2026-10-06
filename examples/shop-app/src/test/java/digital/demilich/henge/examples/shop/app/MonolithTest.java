package digital.demilich.henge.examples.shop.app;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.examples.shop.app.ShopTestSupport.Feed;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Response;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Shop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** One process hosting everything, with no Henge configuration beyond the application's own. */
class MonolithTest {

    static Shop shop;

    @BeforeAll
    static void start() {
        shop = Shop.start("--shop.inventory.jdbc-url=jdbc:h2:mem:monolith;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void stop() {
        shop.close();
    }

    @Test
    void placingAnOrderHoldsItsStockAndCancellingPutsItBack() {
        int before = shop.available("rope");

        Response placed = shop.placeOrder("ada", "rope", 3);
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.body().get("status").asText()).isEqualTo("PLACED");
        assertThat(shop.available("rope")).isEqualTo(before - 3);

        String id = placed.body().get("id").asText();
        assertThat(shop.get("/api/orders/" + id).body().get("customer").asText()).isEqualTo("ada");

        assertThat(shop.post("/api/orders/" + id + "/cancel", null).body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(shop.available("rope")).isEqualTo(before);
    }

    @Test
    void anOrderForMoreThanThereIsHoldsNothing() {
        int before = shop.available("chisel");

        Response refused = shop.post("/api/orders", """
                {"customer": "bob", "items": [{"sku": "lantern", "quantity": 1}, {"sku": "chisel", "quantity": 1000}]}""");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("detail").asText()).contains("chisel: 1000 wanted");
        assertThat(shop.available("chisel")).isEqualTo(before);
    }

    @Test
    void anUnknownOrderIsNotFound() {
        assertThat(shop.get("/api/orders/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
    }

    @Test
    void restockingAddsToWhatIsAvailable() {
        int before = shop.available("lantern");

        assertThat(shop.post("/api/stock/lantern?quantity=4", null).body().get("lantern").asInt()).isEqualTo(before + 4);
    }

    @Test
    void aCustomerGetsABurstOfThreeMessagesAndThenNoMore() {
        for (int i = 0; i < 4; i++) {
            assertThat(shop.placeOrder("cy", "rope", 1).status()).isEqualTo(201);
        }

        // Four orders placed; the fourth notification was refused, and the order went through anyway.
        assertThat(shop.get("/api/customers/cy/notifications").body()).hasSize(3);
    }

    @Test
    void aCustomerWatchingAnOrderIsToldItsStatusAndThenWhenItChanges() throws Exception {
        String id = shop.placeOrder("dee", "rope", 1).body().get("id").asText();

        Feed feed = shop.watch(id);

        assertThat(feed.next()).isEqualTo("PLACED");
        shop.post("/api/orders/" + id + "/cancel", null);
        assertThat(feed.next()).isEqualTo("CANCELLED");
    }

    @Test
    void watchingAnOrderThatDoesNotExistIsRefusedAsNotFound() throws Exception {
        Feed feed = shop.watch("00000000-0000-0000-0000-000000000000");

        assertThat(feed.closedWith()).isEqualTo(4404);
    }
}
