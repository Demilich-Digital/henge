package digital.demilich.henge.spring.leasedfixture.ledger;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = ReportService.class, version = 1)
@RequiresLease("ledger-db")
public class ReportServiceImpl implements ReportService {

    private final Lease lease;

    public ReportServiceImpl(Lease lease) {
        this.lease = lease;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount();
    }
}
