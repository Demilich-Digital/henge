package digital.demilich.henge.spring.leasedfixture.noprovider;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = OrphanService.class, version = 1)
public class OrphanServiceImpl implements OrphanService {

    public OrphanServiceImpl(@RequiresLease("orphan-db") Object resource) {
    }

    @Override
    public String value() {
        return "";
    }
}
