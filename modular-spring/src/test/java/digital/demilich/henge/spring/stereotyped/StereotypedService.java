package digital.demilich.henge.spring.stereotyped;

import digital.demilich.henge.core.ModularService;

/** Kept outside {@code fixture} so other tests' scans don't pick it up. */
@ModularService
public interface StereotypedService {

    String a();
}
