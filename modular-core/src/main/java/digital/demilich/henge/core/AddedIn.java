package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @ModularService} interface method as only existing from {@link #value()} onward.
 *
 * <p>Purely compile-time metadata, read by the {@code modular-processor} annotation processor: it
 * generates a companion {@code {Interface}Skeleton} abstract class providing a throwing
 * implementation of this method, so a {@code @ServiceVersion} implementation whose version
 * predates {@link #value()} doesn't have to implement it at all — see that module's Javadoc for
 * the generation and validation mechanics. Has no effect at runtime; nothing reads this
 * reflectively.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface AddedIn {

    /** The version this method starts existing at. A positive integer. */
    int value();
}
