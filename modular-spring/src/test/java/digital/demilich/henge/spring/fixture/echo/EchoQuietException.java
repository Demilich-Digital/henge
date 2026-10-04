package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ErrorLogLevel;

/** A 500 (no @ErrorStatus) the service doesn't want logged at all. */
@ErrorLogLevel(ErrorLogLevel.Level.NONE)
public class EchoQuietException extends RuntimeException {

    public EchoQuietException(String message) {
        super(message);
    }
}
