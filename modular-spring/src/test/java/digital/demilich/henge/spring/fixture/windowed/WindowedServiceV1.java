package digital.demilich.henge.spring.fixture.windowed;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = WindowedService.class, version = 1)
public class WindowedServiceV1 implements WindowedService {

    @Override
    public String describe() {
        return "v1";
    }
}
