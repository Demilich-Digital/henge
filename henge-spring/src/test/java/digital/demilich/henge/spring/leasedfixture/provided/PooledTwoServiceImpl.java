package digital.demilich.henge.spring.leasedfixture.provided;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = PooledTwoService.class, version = 1)
public class PooledTwoServiceImpl implements PooledTwoService {

    private final FakePool pool;

    public PooledTwoServiceImpl(@RequiresLease("pool-db") FakePool pool) {
        this.pool = pool;
    }

    @Override
    public String pool() {
        return System.identityHashCode(pool) + ":" + pool.size();
    }
}
