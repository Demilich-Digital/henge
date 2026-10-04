package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = AlphaService.class, version = 1)
public class AlphaServiceV1Impl implements AlphaService {

    private final Lease lease;

    public AlphaServiceV1Impl(@RequiresLease("shared-db") Lease lease) {
        this.lease = lease;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount();
    }
}
