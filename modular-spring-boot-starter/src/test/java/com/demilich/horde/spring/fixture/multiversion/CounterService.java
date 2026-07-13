package com.demilich.horde.spring.fixture.multiversion;

import com.demilich.horde.core.ModularService;

@ModularService(name = "counter-service", defaultVersion = "1")
public interface CounterService {

    String describe();
}
