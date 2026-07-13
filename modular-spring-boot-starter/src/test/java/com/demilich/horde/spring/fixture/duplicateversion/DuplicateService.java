package com.demilich.horde.spring.fixture.duplicateversion;

import com.demilich.horde.core.ModularService;

@ModularService(name = "duplicate-service")
public interface DuplicateService {

    void doThing();
}
