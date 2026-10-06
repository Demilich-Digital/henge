package digital.demilich.henge.spring.everynodefixture.plain;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = GatewayService.class, version = 1)
public class GatewayServiceImpl implements GatewayService {

    @Override
    public String route(String path) {
        return "routed " + path;
    }
}
