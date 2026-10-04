package digital.demilich.henge.spring.leasedfixture.ledger;

import digital.demilich.henge.core.ModularService;

@ModularService
public interface LedgerService {

    /** What the implementation was granted: {@code <lease>:<amount>}. */
    String grant();
}
