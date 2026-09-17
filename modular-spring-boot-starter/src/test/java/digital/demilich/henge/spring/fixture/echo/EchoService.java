package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ModularService;

@ModularService(name = "echo-service")
public interface EchoService {

    String echo(String value);
}
