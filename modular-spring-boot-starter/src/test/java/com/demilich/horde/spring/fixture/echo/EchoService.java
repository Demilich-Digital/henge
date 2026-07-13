package com.demilich.horde.spring.fixture.echo;

import com.demilich.horde.core.ModularService;

@ModularService(name = "echo-service")
public interface EchoService {

    String echo(String value);
}
