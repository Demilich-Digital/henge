package digital.demilich.henge.examples.shop.notifications;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.ImmutableList;

/** Messages to customers, limited so that no customer is flooded. */
@HengeService
public interface NotificationService {

    /** Sends {@code text} to {@code customer}, unless they've had too many lately; whether it was sent. */
    boolean notify(String customer, String text);

    /** What {@code customer} has been sent, oldest first. */
    ImmutableList<Notification> sentTo(String customer);
}
