package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = AlphaService.class, version = 2)
public class AlphaServiceV2Impl implements AlphaService {

    private final Lease lease;

    public AlphaServiceV2Impl(@RequiresLease("shared-db") Lease lease) {
        this.lease = lease;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount();
    }
}
