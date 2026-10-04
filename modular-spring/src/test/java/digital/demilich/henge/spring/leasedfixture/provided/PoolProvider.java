package digital.demilich.henge.spring.leasedfixture.provided;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.ResourceProvider;

@LeasedResource("pool-db")
public class PoolProvider implements ResourceProvider<FakePool> {

    @Override
    public FakePool open(Lease lease) {
        return new FakePool(lease.amount());
    }
}
