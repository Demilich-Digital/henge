package com.demilich.horde.spring.fixture.duplicateversion;

import com.demilich.horde.core.ServiceVersion;

@ServiceVersion(value = DuplicateService.class, version = "1")
public class DuplicateServiceImplA implements DuplicateService {

    @Override
    public void doThing() {
    }
}
