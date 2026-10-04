package digital.demilich.henge.examples.shop.orders;

import digital.demilich.henge.core.ErrorStatus;

/**
 * No order has that id. A {@code 404} tells {@code internal-rest} that the call had no effect, so it
 * may be tried on another host; a lookup that found nothing qualifies.
 */
@ErrorStatus(404)
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
