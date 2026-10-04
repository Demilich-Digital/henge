package digital.demilich.henge.spring.leasedfixture.two;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

/** A {@code Lease} parameter that doesn't say which lease it is: rejected at startup. */
@ServiceVersion(value = TwoLeaseService.class, version = 1)
public class TwoLeaseServiceImpl implements TwoLeaseService {

    public TwoLeaseServiceImpl(@RequiresLease("db-a") Lease a, Lease b) {
    }

    @Override
    public String grant() {
        return "";
    }
}
