package digital.demilich.henge.spring.fixture.duplicateversion;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = DuplicateService.class, version = "1")
public class DuplicateServiceImplA implements DuplicateService {

    @Override
    public void doThing() {
    }
}
