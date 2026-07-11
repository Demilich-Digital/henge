package io.modular.spring.fixture.multiversion;

import io.modular.core.ModularService;

@ModularService(name = "counter-service", defaultVersion = "1")
public interface CounterService {

    String describe();
}
