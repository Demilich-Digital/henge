package io.modular.spring.fixture.duplicateversion;

import io.modular.core.ServiceVersion;

@ServiceVersion(value = DuplicateService.class, version = "1")
public class DuplicateServiceImplA implements DuplicateService {

    @Override
    public void doThing() {
    }
}
