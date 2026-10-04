package digital.demilich.henge.examples.shop.inventory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.ResourceProvider;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The inventory database's connection pool, built only on a node granted the {@code inventory-db}
 * lease, and sized from it: however many nodes host inventory, the database sees at most the lease's
 * capacity in connections.
 *
 * <p>The default is an in-memory database, one per process, which is enough while one process hosts
 * inventory. Processes that share the inventory need a database they can all reach
 * ({@code shop.inventory.jdbc-url}).
 */
@LeasedResource("inventory-db")
public class InventoryDatabase implements ResourceProvider<DataSource> {

    private final String jdbcUrl;

    public InventoryDatabase(@Value("${shop.inventory.jdbc-url:jdbc:h2:mem:inventory;DB_CLOSE_DELAY=-1}") String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public DataSource open(Lease lease) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("inventory-db");
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(lease.amount());
        HikariDataSource pool = new HikariDataSource(config);
        createSchema(new JdbcTemplate(pool));
        return pool;
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS stock (sku VARCHAR(64) PRIMARY KEY, available INT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS reservation (order_id UUID NOT NULL, sku VARCHAR(64) NOT NULL,"
                + " quantity INT NOT NULL, PRIMARY KEY (order_id, sku))");
        // Something to sell on a first start; a database that has stock already keeps it.
        jdbc.update("""
                INSERT INTO stock (sku, available)
                SELECT sku, available FROM (VALUES ('rope', 20), ('lantern', 5), ('chisel', 2)) AS seed (sku, available)
                WHERE NOT EXISTS (SELECT 1 FROM stock)""");
    }
}
