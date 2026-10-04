package digital.demilich.henge.spring.fixture.windowed;

import digital.demilich.henge.core.HengeService;

@HengeService(name = "windowed-service", defaultVersion = 3)
public interface WindowedService {

    String describe();
}
