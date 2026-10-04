package digital.demilich.henge.spring.ratelimitfixture;

import digital.demilich.henge.core.HengeService;

@HengeService
public interface SendService {

    /** Whether a message to {@code recipient} was let through. */
    boolean send(String recipient);
}
