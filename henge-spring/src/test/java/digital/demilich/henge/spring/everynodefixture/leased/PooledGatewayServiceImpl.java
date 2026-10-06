package digital.demilich.henge.spring.everynodefixture.leased;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = PooledGatewayService.class, version = 1)
public class PooledGatewayServiceImpl implements PooledGatewayService {

    public PooledGatewayServiceImpl(@RequiresLease("gateway-db") Lease lease) {
    }

    @Override
    public String route(String path) {
        return path;
    }
}
