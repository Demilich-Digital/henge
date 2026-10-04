package digital.demilich.henge.examples.shop.notifications;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.RateLimited;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.ServiceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Sends" by logging, and keeps what it sent in memory so the example can show it.
 *
 * <p>Each customer has their own bucket under the {@code customer-notifications} limit
 * ({@code henge.rate-limits.customer-notifications}). With a shared ephemeral store the bucket is the
 * cluster's, so a customer gets no more messages for there being more processes sending them.
 */
@ServiceVersion(value = NotificationService.class, version = 1)
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final RateLimiter perCustomer;
    private final Map<String, List<Notification>> sent = new ConcurrentHashMap<>();

    public NotificationServiceImpl(@RateLimited("customer-notifications") RateLimiter perCustomer) {
        this.perCustomer = perCustomer;
    }

    @Override
    public boolean notify(String customer, String text) {
        if (!perCustomer.tryAcquire(customer)) {
            log.info("Not sending to {}, who has had enough lately: {}", customer, text);
            return false;
        }
        log.info("To {}: {}", customer, text);
        sent.computeIfAbsent(customer, c -> new CopyOnWriteArrayList<>()).add(new Notification(customer, text, Instant.now()));
        return true;
    }

    @Override
    public ImmutableList<Notification> sentTo(String customer) {
        return ImmutableList.copyOf(sent.getOrDefault(customer, List.of()));
    }
}
