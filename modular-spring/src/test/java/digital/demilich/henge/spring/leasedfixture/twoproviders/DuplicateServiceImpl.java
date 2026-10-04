package digital.demilich.henge.spring.leasedfixture.twoproviders;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = DuplicateService.class, version = 1)
public class DuplicateServiceImpl implements DuplicateService {

    public DuplicateServiceImpl(@RequiresLease("dup-db") Object resource) {
    }

    @Override
    public String value() {
        return "";
    }
}
