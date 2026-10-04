package digital.demilich.henge.spring.fixture.windowed;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = WindowedService.class, version = 3)
public class WindowedServiceV3 implements WindowedService {

    @Override
    public String describe() {
        return "v3";
    }
}
