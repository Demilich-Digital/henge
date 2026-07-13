package io.modular.spring.fixture.echo;

import io.modular.core.ModularService;

@ModularService(name = "echo-service")
public interface EchoService {

    String echo(String value);

    /** Always throws {@link EchoFailureException}, for exercising remote-exception reconstruction. */
    void explode(String reason);
}
