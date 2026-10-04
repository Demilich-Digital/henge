package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.HengeService;

@HengeService(name = "echo-service")
public interface EchoService {

    String echo(String value);

    /** Always throws {@link EchoFailureException}, for exercising remote-exception reconstruction. */
    void explode(String reason);

    /** Exercises {@link ImmutableList} argument/return round-tripping through real HTTP dispatch. */
    ImmutableList<String> upperCaseAll(ImmutableList<String> values);

    /** Returns its argument: an argument and a return value that must both arrive exactly as sent. */
    Measurement measure(Measurement measurement);
}
