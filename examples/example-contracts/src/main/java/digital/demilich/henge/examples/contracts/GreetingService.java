package digital.demilich.henge.examples.contracts;

import digital.demilich.henge.core.ModularService;

@ModularService(name = "greeting-service")
public interface GreetingService {

    String greet(String name);
}
