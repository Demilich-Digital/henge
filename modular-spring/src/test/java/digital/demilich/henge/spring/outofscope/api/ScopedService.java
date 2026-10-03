package digital.demilich.henge.spring.outofscope.api;

import digital.demilich.henge.core.ModularService;

/** Deliberately in a package the test's @EnableModularServices doesn't scan; kept outside {@code fixture}. */
@ModularService
public interface ScopedService {

    String a();
}
