package io.modular.spring.fixture.counter;

import io.modular.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = "1")
public class CounterServiceV1 implements CounterService {

    @Override
    public String describe() {
        return "v1";
    }
}
