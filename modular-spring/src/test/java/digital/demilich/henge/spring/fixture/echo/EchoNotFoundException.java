package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ErrorStatus;

/** A business exception that asks the dispatcher to answer 404 instead of the default 500. */
@ErrorStatus(404)
public class EchoNotFoundException extends RuntimeException {

    public EchoNotFoundException(String message) {
        super(message);
    }
}
