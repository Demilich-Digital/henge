package digital.demilich.henge.spring.duplicateversion;

import digital.demilich.henge.core.ServiceVersion;

/**
 * Deliberately claims the same version as {@link DuplicateServiceImplA}, to exercise the registrar's
 * fail-fast path -- standing in for implementations in different modules, which the processor can't
 * see together. This module's tests compile without the processor, which would reject it.
 */
@ServiceVersion(value = DuplicateService.class, version = 1)
public class DuplicateServiceImplB implements DuplicateService {

    @Override
    public void doThing() {
    }
}
