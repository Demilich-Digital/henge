package digital.demilich.henge.spring.leasedfixture.versioned;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = BetaService.class, version = 1)
@RequiresLease("shared-db")
public class BetaServiceV1Impl implements BetaService {

    private final Lease lease;

    public BetaServiceV1Impl(Lease lease) {
        this.lease = lease;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount();
    }
}
