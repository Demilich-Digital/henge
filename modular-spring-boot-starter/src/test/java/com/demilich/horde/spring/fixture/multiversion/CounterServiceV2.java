package com.demilich.horde.spring.fixture.multiversion;

import com.demilich.horde.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = "2")
public class CounterServiceV2 implements CounterService {

    @Override
    public String describe() {
        return "v2";
    }
}
