package digital.demilich.henge.spring.fixture.echo;

import digital.demilich.henge.core.ErrorLogLevel;
import digital.demilich.henge.core.ErrorStatus;

/** A 4xx the service wants logged at WARN rather than the default debug. */
@ErrorStatus(409)
@ErrorLogLevel(ErrorLogLevel.Level.WARN)
public class EchoWarnedException extends RuntimeException {

    public EchoWarnedException(String message) {
        super(message);
    }
}
