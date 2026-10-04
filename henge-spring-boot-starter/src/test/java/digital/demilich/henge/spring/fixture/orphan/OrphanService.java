package digital.demilich.henge.spring.fixture.orphan;

import digital.demilich.henge.core.HengeService;

/** Deliberately has no implementation on the classpath, to exercise the fail-fast path. */
@HengeService(name = "orphan-service")
public interface OrphanService {

    void doThing();
}
