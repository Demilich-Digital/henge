package digital.demilich.henge.spring.fixture.windowed;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = WindowedService.class, version = 2)
public class WindowedServiceV2 implements WindowedService {

    @Override
    public String describe() {
        return "v2";
    }
}
