package digital.demilich.henge.spring.fixture.counter;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = CounterService.class, version = 2)
public class CounterServiceV2 implements CounterService {

    @Override
    public String describe() {
        return "v2";
    }
}
