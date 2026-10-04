package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.ModularService;

@ModularService(defaultVersion = 2)
public interface AlphaService {

    /** What the implementation was granted: {@code <lease>:<amount>}. */
    String grant();
}
