package digital.demilich.henge.spring.everynodefixture.plain;

import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.RunOnEveryNode;

@RunOnEveryNode
@HengeService
public interface GatewayService {

    String route(String path);
}
