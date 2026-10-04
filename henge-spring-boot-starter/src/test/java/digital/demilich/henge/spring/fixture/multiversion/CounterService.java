package digital.demilich.henge.spring.fixture.multiversion;

import digital.demilich.henge.core.HengeService;

@HengeService(name = "counter-service", defaultVersion = 1)
public interface CounterService {

    String describe();
}
