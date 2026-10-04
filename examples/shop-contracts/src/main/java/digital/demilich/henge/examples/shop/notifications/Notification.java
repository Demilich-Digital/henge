package digital.demilich.henge.examples.shop.notifications;

import java.time.Instant;

public record Notification(String customer, String text, Instant sentAt) {
}
