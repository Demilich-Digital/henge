package digital.demilich.henge.spring.badname;

import digital.demilich.henge.core.HengeService;

/**
 * A name the processor would reject; this module's tests compile without it, standing in for an
 * interface compiled without the processor. Kept outside {@code fixture} so other tests' scans
 * don't pick it up.
 */
@HengeService(name = "billing/v2")
public interface SlashedNameService {

    String a();
}
