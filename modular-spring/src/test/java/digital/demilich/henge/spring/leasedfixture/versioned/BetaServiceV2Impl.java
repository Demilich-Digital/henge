package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = BetaService.class, version = 2)
public class BetaServiceV2Impl implements BetaService {

    private final Lease lease;

    public BetaServiceV2Impl(@RequiresLease("shared-db") Lease lease) {
        this.lease = lease;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount();
    }
}
