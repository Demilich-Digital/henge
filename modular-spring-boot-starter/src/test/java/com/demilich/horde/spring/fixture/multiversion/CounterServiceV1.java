package com.demilich.horde.spring.fixture.multiversion;

import com.demilich.horde.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = "1")
public class CounterServiceV1 implements CounterService {

    @Override
    public String describe() {
        return "v1";
    }
}
