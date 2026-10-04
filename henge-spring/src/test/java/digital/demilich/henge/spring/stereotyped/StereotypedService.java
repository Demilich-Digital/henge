package digital.demilich.henge.spring.stereotyped;

import digital.demilich.henge.core.HengeService;

/** Kept outside {@code fixture} so other tests' scans don't pick it up. */
@HengeService
public interface StereotypedService {

    String a();
}
