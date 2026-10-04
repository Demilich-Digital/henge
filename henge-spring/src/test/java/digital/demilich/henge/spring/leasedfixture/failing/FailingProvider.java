package digital.demilich.henge.spring.leasedfixture.failing;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.ResourceProvider;

@LeasedResource("fail-db")
public class FailingProvider implements ResourceProvider<Object> {

    @Override
    public Object open(Lease lease) throws Exception {
        throw new IllegalStateException("database is down");
    }
}
