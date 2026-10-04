package digital.demilich.henge.spring.leasedfixture.failing;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = FailingService.class, version = 1)
public class FailingServiceImpl implements FailingService {

    public FailingServiceImpl(@RequiresLease("fail-db") Object resource) {
    }

    @Override
    public String value() {
        return "";
    }
}
