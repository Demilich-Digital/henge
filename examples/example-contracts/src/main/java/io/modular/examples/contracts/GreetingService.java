package io.modular.examples.contracts;

import io.modular.core.ModularService;

@ModularService(name = "greeting-service")
public interface GreetingService {

    String greet(String name);
}
