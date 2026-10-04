package digital.demilich.henge.core;

/**
 * Thrown by a generated {@code {Interface}Skeleton} stub method — reached when a
 * {@code @ServiceVersion} implementation didn't override a method outside the version range it
 * declared support for (via {@link AddedIn} / {@link DeprecatedSince}).
 *
 * <p>{@code 501 Not Implemented} over {@code internal-rest}: the framework knows exactly what went
 * wrong -- the caller asked a version for a method outside its range -- so this isn't one of the
 * unclassifiable failures {@code 500} is reserved for. Logged at debug by the serving process, not
 * as an error: it's the caller's mistake.
 */
@ErrorStatus(501)
@ErrorLogLevel(ErrorLogLevel.Level.DEBUG)
public class ServiceVersionUnsupportedException extends RuntimeException {

    public ServiceVersionUnsupportedException(String message) {
        super(message);
    }
}
