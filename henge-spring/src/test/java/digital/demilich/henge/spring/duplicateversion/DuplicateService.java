package digital.demilich.henge.spring.duplicateversion;

import digital.demilich.henge.core.HengeService;

@HengeService(name = "duplicate-service")
public interface DuplicateService {

    void doThing();
}
