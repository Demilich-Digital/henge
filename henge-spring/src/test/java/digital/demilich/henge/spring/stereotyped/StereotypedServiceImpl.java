package digital.demilich.henge.spring.stereotyped;

import digital.demilich.henge.core.ServiceVersion;
import org.springframework.stereotype.Service;

/** The processor would reject this; this module's tests compile without it. */
@Service
@ServiceVersion(value = StereotypedService.class, version = 1)
public class StereotypedServiceImpl implements StereotypedService {

    @Override
    public String a() {
        return "a";
    }
}
