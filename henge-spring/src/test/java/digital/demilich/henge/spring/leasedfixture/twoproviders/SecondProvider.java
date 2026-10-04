package digital.demilich.henge.spring.leasedfixture.twoproviders;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.ResourceProvider;

@LeasedResource("dup-db")
public class SecondProvider implements ResourceProvider<Object> {

    @Override
    public Object open(Lease lease) throws Exception {
        return new Object();
    }
}
