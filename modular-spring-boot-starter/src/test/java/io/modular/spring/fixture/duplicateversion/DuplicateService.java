package io.modular.spring.fixture.duplicateversion;

import io.modular.core.ModularService;

@ModularService(name = "duplicate-service")
public interface DuplicateService {

    void doThing();
}
