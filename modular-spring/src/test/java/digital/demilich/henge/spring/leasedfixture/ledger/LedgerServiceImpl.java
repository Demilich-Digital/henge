package digital.demilich.henge.spring.leasedfixture.ledger;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;
import org.springframework.core.env.Environment;

/** Takes its {@code Lease} next to an ordinary dependency, to show Spring still resolves the rest. */
@ServiceVersion(value = LedgerService.class, version = 1)
@RequiresLease("ledger-db")
public class LedgerServiceImpl implements LedgerService {

    private final Lease lease;
    private final Environment environment;

    public LedgerServiceImpl(Lease lease, Environment environment) {
        this.lease = lease;
        this.environment = environment;
    }

    @Override
    public String grant() {
        return lease.name() + ":" + lease.amount() + (environment == null ? "!" : "");
    }
}
