package digital.demilich.henge.examples.shop.app;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.examples.shop.app.ShopTestSupport.Feed;
import digital.demilich.henge.examples.shop.app.ShopTestSupport.Shop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two identical processes, each hosting everything, sharing an ephemeral store (Redis). Nothing tells
 * either where the other is: each advertises its own address.
 *
 * <p>The database admits one process's pool ({@code inventory-db} capacity 5, 5 to a process), so the
 * first process gets the lease and builds inventory, and the second is refused, never opens a connection,
 * and reaches the first's inventory through its advertisement. Rate limits are the cluster's too.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    static Shop first;
    static Shop second;

    /**
     * Each node is given a database of its own, which only the node granted the lease opens: stock that
     * changes on the first node, through the second, went through the first node's inventory.
     */
    static Shop startNode(String database) {
        return Shop.start("--henge.store.type=redis",
                "--henge.store.redis.uri=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "--henge.leases.inventory-db.capacity=5",
                "--henge.advertise.url=http://localhost:${server.port}",
                "--shop.inventory.jdbc-url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    }

    @BeforeAll
    static void start() {
        first = startNode("first");
        second = startNode("second");
    }

    @AfterAll
    static void stop() {
        second.close();
        first.close();
    }

    @Test
    void onlyOneProcessHoldsTheDatabaseLeaseAndTheOtherReachesItsInventory() {
        int before = first.available("rope");

        assertThat(second.placeOrder("ada", "rope", 2).status()).isEqualTo(201);

        // The second process's order held stock in the first process's database.
        assertThat(first.available("rope")).isEqualTo(before - 2);
        assertThat(second.available("rope")).isEqualTo(before - 2);
    }

    @Test
    void aCustomerIsLimitedAcrossTheWholeCluster() {
        for (int i = 0; i < 2; i++) {
            first.placeOrder("bob", "rope", 1);
            second.placeOrder("bob", "rope", 1);
        }

        // Four orders, two through each process: one bucket of three between them.
        int sent = first.get("/api/customers/bob/notifications").body().size()
                + second.get("/api/customers/bob/notifications").body().size();
        assertThat(sent).isEqualTo(3);
    }

    @Test
    void aFeedNeedsNothingFromTheStoreOnceItIsOpen() throws Exception {
        String id = second.placeOrder("cat", "rope", 1).body().get("id").asText();
        Feed feed = second.watch(id);
        assertThat(feed.next()).isEqualTo("PLACED");

        second.post("/api/orders/" + id + "/cancel", null);

        assertThat(feed.next()).isEqualTo("CANCELLED");
    }
}
