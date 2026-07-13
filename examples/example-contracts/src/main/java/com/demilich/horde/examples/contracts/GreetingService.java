package com.demilich.horde.examples.contracts;

import com.demilich.horde.core.ModularService;

@ModularService(name = "greeting-service")
public interface GreetingService {

    String greet(String name);
}
