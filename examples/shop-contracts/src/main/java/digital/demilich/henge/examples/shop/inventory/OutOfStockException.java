package digital.demilich.henge.examples.shop.inventory;

import digital.demilich.henge.core.ErrorStatus;

/**
 * An order asked for more than is available. Nothing is held when it's thrown. Over
 * {@code internal-rest} it answers {@code 409}, and the caller gets this exception back.
 */
@ErrorStatus(409)
public class OutOfStockException extends RuntimeException {

    public OutOfStockException(String message) {
        super(message);
    }
}
