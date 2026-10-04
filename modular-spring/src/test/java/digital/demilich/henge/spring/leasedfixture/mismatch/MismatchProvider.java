package digital.demilich.henge.spring.leasedfixture.mismatch;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.ResourceProvider;

@LeasedResource("mm-db")
public class MismatchProvider implements ResourceProvider<String> {

    @Override
    public String open(Lease lease) throws Exception {
        return "a string";
    }
}
