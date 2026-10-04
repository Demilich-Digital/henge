package digital.demilich.henge.examples.shop.inventory;

import digital.demilich.henge.core.ImmutableMap;
import digital.demilich.henge.core.ImmutableSet;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Version 2: version 1, plus {@code availability}. Both run side by side over the same database, so a
 * caller that pins version 2 and one that takes the default see the same stock. Both versions' leases are
 * one node-level claim, and they share its one pool.
 */
@ServiceVersion(value = InventoryService.class, version = 2)
public class InventoryServiceImplV2 extends InventoryServiceImpl {

    public InventoryServiceImplV2(@RequiresLease("inventory-db") DataSource database) {
        super(database);
    }

    @Override
    public ImmutableMap<String, Integer> availability(ImmutableSet<String> skus) {
        Map<String, Integer> available = new HashMap<>();
        skus.forEach(sku -> available.put(sku, 0));
        if (!skus.isEmpty()) {
            new NamedParameterJdbcTemplate(jdbc).query("SELECT sku, available FROM stock WHERE sku IN (:skus)",
                    Map.of("skus", skus), row -> {
                        available.put(row.getString("sku"), row.getInt("available"));
                    });
        }
        return ImmutableMap.copyOf(available);
    }
}
