package digital.demilich.henge.spring.fixture.orphan;

import digital.demilich.henge.core.ModularService;

/** Deliberately has no implementation on the classpath, to exercise the fail-fast path. */
@ModularService(name = "orphan-service")
public interface OrphanService {

    void doThing();
}
