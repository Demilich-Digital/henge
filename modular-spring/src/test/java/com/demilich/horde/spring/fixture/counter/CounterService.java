package com.demilich.horde.spring.fixture.counter;

import com.demilich.horde.core.ModularService;

@ModularService(name = "counter-service", defaultVersion = "1")
public interface CounterService {

    String describe();
}
