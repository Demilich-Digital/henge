package digital.demilich.henge.spring.fixture.counter;

import digital.demilich.henge.core.ModularService;

@ModularService(name = "counter-service", defaultVersion = "1")
public interface CounterService {

    String describe();
}
