package digital.demilich.henge.spring.fixture.multiversion;

import digital.demilich.henge.core.ServiceVersion;
import org.springframework.stereotype.Component;

/** Pins its {@link CounterService} dependency to version "2" explicitly. */
@Component
public class PinnedConsumer {

    private final CounterService counterService;

    public PinnedConsumer(@ServiceVersion(value = CounterService.class, version = 2) CounterService counterService) {
        this.counterService = counterService;
    }

    public String describe() {
        return counterService.describe();
    }
}
