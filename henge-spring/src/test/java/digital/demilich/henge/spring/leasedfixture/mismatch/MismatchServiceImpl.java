package digital.demilich.henge.spring.leasedfixture.mismatch;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = MismatchService.class, version = 1)
public class MismatchServiceImpl implements MismatchService {

    public MismatchServiceImpl(@RequiresLease("mm-db") Integer resource) {
    }

    @Override
    public String value() {
        return "";
    }
}
