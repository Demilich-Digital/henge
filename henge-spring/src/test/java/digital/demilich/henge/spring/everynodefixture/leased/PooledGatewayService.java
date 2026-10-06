package digital.demilich.henge.spring.everynodefixture.leased;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.HengeRunOnEveryNode;

@HengeRunOnEveryNode
@HengeService
public interface PooledGatewayService {

    String route(String path);
}
