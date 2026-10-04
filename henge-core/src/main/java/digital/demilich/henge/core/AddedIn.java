package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @HengeService} interface method as only existing from {@link #value()} onward.
 *
 * <p>Purely compile-time metadata, read by the {@code henge-processor} annotation processor: it
 * generates a companion {@code {Interface}Skeleton} abstract class providing a throwing
 * implementation of this method, so a {@code @ServiceVersion} implementation whose version
 * predates {@link #value()} doesn't have to implement it at all — see that module's Javadoc for
 * the generation and validation mechanics. Has no effect at runtime; nothing reads this
 * reflectively.
 *
 * <p>{@code CLASS} retention, not {@code SOURCE}: an implementation is usually compiled in a
 * different module from its interface, and the processor then sees the interface only as a class
 * file -- a {@code SOURCE}-retained annotation would be gone, and the implementation silently
 * unchecked.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface AddedIn {

    /** The version this method starts existing at. A positive integer. */
    int value();
}
