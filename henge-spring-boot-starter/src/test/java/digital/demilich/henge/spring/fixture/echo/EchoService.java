package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.HengeService;

@HengeService(name = "echo-service")
public interface EchoService {

    String echo(String value);
}
