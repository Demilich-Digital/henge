package digital.demilich.henge.spring.fixture.multiversion;

import digital.demilich.henge.core.ModularService;

@ModularService(name = "counter-service", defaultVersion = 1)
public interface CounterService {

    String describe();
}
