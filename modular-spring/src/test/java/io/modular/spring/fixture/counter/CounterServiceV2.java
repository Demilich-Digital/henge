package io.modular.spring.fixture.counter;

import io.modular.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = "2")
public class CounterServiceV2 implements CounterService {

    @Override
    public String describe() {
        return "v2";
    }
}
