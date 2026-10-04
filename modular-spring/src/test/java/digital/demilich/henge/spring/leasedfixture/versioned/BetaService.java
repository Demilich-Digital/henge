package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.ModularService;

@ModularService(defaultVersion = 2)
public interface BetaService {

    /** What the implementation was granted: {@code <lease>:<amount>}. */
    String grant();
}
