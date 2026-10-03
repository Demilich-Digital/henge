package digital.demilich.henge.spring.fixture.duplicateversion;

import digital.demilich.henge.core.ServiceVersion;

/** Deliberately claims the same version as {@link DuplicateServiceImplA}, to exercise the fail-fast path. */
@ServiceVersion(value = DuplicateService.class, version = 1)
public class DuplicateServiceImplB implements DuplicateService {

    @Override
    public void doThing() {
    }
}
