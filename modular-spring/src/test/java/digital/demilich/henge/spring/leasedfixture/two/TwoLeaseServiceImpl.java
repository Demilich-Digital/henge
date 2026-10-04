package digital.demilich.henge.spring.leasedfixture.two;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

/** Two leases, and a {@code Lease} parameter that doesn't say which: rejected at startup. */
@ServiceVersion(value = TwoLeaseService.class, version = 1)
@RequiresLease("db-a")
@RequiresLease("db-b")
public class TwoLeaseServiceImpl implements TwoLeaseService {

    public TwoLeaseServiceImpl(Lease lease) {
    }

    @Override
    public String grant() {
        return "";
    }
}
