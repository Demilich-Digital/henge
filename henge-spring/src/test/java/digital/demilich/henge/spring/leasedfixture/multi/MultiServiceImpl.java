package digital.demilich.henge.spring.leasedfixture.multi;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

/** Needs two leases, each named on its parameter. */
@ServiceVersion(value = MultiService.class, version = 1)
public class MultiServiceImpl implements MultiService {

    private final Lease a;
    private final Lease b;

    public MultiServiceImpl(@RequiresLease("db-a") Lease a, @RequiresLease("db-b") Lease b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public String grant() {
        return a.name() + ":" + a.amount() + "," + b.name() + ":" + b.amount();
    }
}
