package digital.demilich.henge.spring.leasedfixture.provided;

import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = PooledOneService.class, version = 1)
public class PooledOneServiceImpl implements PooledOneService {

    private final FakePool pool;

    public PooledOneServiceImpl(@RequiresLease("pool-db") FakePool pool) {
        this.pool = pool;
    }

    @Override
    public String pool() {
        return System.identityHashCode(pool) + ":" + pool.size();
    }
}
