package digital.demilich.henge.spring.everynodefixture.plain;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.HengeRunOnEveryNode;

@HengeRunOnEveryNode
@HengeService
public interface GatewayService {

    String route(String path);
}
