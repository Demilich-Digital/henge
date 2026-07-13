package com.demilich.horde.spring.fixture.echo;

import com.demilich.horde.core.ImmutableList;
import com.demilich.horde.core.ModularService;

@ModularService(name = "echo-service")
public interface EchoService {

    String echo(String value);

    /** Always throws {@link EchoFailureException}, for exercising remote-exception reconstruction. */
    void explode(String reason);

    /** Exercises {@link ImmutableList} argument/return round-tripping through real HTTP dispatch. */
    ImmutableList<String> upperCaseAll(ImmutableList<String> values);
}
