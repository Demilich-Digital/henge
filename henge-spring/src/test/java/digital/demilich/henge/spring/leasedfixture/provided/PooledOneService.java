package digital.demilich.henge.spring.leasedfixture.provided;

import digital.demilich.henge.core.HengeService;

@HengeService
public interface PooledOneService {

    /** Identifies the pool this service was given, and how big it is. */
    String pool();
}
