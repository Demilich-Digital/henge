package digital.demilich.henge.spring.leasedfixture.provided;

import digital.demilich.henge.core.ModularService;

@ModularService
public interface PooledTwoService {

    /** Identifies the pool this service was given, and how big it is. */
    String pool();
}
