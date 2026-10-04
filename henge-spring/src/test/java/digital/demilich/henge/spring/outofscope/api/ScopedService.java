package digital.demilich.henge.spring.outofscope.api;

import digital.demilich.henge.core.HengeService;

/** Deliberately in a package the test's @EnableHengeServices doesn't scan; kept outside {@code fixture}. */
@HengeService
public interface ScopedService {

    String a();
}
