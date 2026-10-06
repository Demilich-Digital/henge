package digital.demilich.henge.spring.everynodefixture.leased;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.RunOnEveryNode;

@RunOnEveryNode
@HengeService
public interface PooledGatewayService {

    String route(String path);
}
