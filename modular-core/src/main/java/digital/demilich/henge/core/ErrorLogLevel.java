package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chooses how the serving process logs the annotated exception (or a subclass) when an
 * implementation throws it during an {@code internal-rest} dispatch -- independently of the HTTP
 * status it's answered with ({@link ErrorStatus}). Without it, a failure answered with a {@code 5xx}
 * is logged at {@code ERROR} with its stack trace (the caller only ever gets the exception's type and
 * message, so otherwise the stack trace would exist nowhere), and a {@code 4xx} at {@code DEBUG}.
 *
 * <pre>{@code
 * @ErrorStatus(503)
 * @ErrorLogLevel(ErrorLogLevel.Level.WARN)
 * public class InventoryUnavailableException extends RuntimeException { ... }
 * }</pre>
 *
 * <p>{@link Level#NONE} logs nothing; any other level logs there, with the stack trace. Has no effect
 * when the service is embedded: the exception simply propagates to the caller.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ErrorLogLevel {

    Level value();

    enum Level {
        NONE, TRACE, DEBUG, INFO, WARN, ERROR
    }
}
