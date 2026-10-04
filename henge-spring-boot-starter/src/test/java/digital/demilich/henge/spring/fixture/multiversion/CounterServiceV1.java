package digital.demilich.henge.spring.fixture.multiversion;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = 1)
public class CounterServiceV1 implements CounterService {

    @Override
    public String describe() {
        return "v1";
    }
}
