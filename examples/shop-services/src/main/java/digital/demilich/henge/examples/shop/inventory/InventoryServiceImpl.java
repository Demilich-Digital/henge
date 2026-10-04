package digital.demilich.henge.examples.shop.inventory;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Version 1. All of its state is in the database, none in this object, so any number of processes can
 * host it at once, and every version of it sees the same stock.
 *
 * <p>It extends the generated {@link InventoryServiceSkeleton} rather than implementing
 * {@link InventoryService}, so it needn't implement {@code availability}, which is
 * {@code @AddedIn(2)}: called on this version, that throws
 * {@link digital.demilich.henge.core.ServiceVersionUnsupportedException}.
 */
@ServiceVersion(value = InventoryService.class, version = 1)
public class InventoryServiceImpl extends InventoryServiceSkeleton {

    protected final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public InventoryServiceImpl(@RequiresLease("inventory-db") DataSource database) {
        this.jdbc = new JdbcTemplate(database);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
    }

    @Override
    public void reserve(UUID orderId, ImmutableList<LineItem> items) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (LineItem item : items) {
            if (item.quantity() < 1) {
                throw new IllegalArgumentException("A line item needs a positive quantity, got " + item);
            }
            wanted.merge(item.sku(), item.quantity(), Integer::sum);
        }
        // One transaction: a sku that runs short undoes what the earlier ones took.
        transactions.executeWithoutResult(status -> wanted.forEach((sku, quantity) -> {
            int taken = jdbc.update("UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?",
                    quantity, sku, quantity);
            if (taken == 0) {
                throw new OutOfStockException(sku + ": " + quantity + " wanted, " + available(sku) + " available");
            }
            jdbc.update("INSERT INTO reservation (order_id, sku, quantity) VALUES (?, ?, ?)", orderId, sku, quantity);
        }));
    }

    @Override
    public void release(UUID orderId) {
        transactions.executeWithoutResult(status -> {
            List<LineItem> held = jdbc.query("SELECT sku, quantity FROM reservation WHERE order_id = ? FOR UPDATE",
                    (row, n) -> new LineItem(row.getString("sku"), row.getInt("quantity")), orderId);
            for (LineItem item : held) {
                jdbc.update("UPDATE stock SET available = available + ? WHERE sku = ?", item.quantity(), item.sku());
            }
            jdbc.update("DELETE FROM reservation WHERE order_id = ?", orderId);
        });
    }

    @Override
    public int available(String sku) {
        List<Integer> available = jdbc.queryForList("SELECT available FROM stock WHERE sku = ?", Integer.class, sku);
        return available.isEmpty() ? 0 : available.get(0);
    }

    @Override
    public void restock(String sku, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Restock a positive quantity, got " + quantity);
        }
        transactions.executeWithoutResult(status -> {
            if (jdbc.update("UPDATE stock SET available = available + ? WHERE sku = ?", quantity, sku) == 0) {
                jdbc.update("INSERT INTO stock (sku, available) VALUES (?, ?)", sku, quantity);
            }
        });
    }
}
