package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ErrorStatus;

/** Annotated with a status outside 4xx/5xx, which the dispatcher must ignore in favor of 500. */
@ErrorStatus(200)
public class EchoBadStatusException extends RuntimeException {

    public EchoBadStatusException(String message) {
        super(message);
    }
}
