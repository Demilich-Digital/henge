package io.modular.spring.fixture.counter;

import org.springframework.stereotype.Component;

/** Depends on {@link CounterService} with no version qualifier — should resolve to the default (v1). */
@Component
public class DefaultConsumer {

    private final CounterService counterService;

    public DefaultConsumer(CounterService counterService) {
        this.counterService = counterService;
    }

    public String describe() {
        return counterService.describe();
    }
}
