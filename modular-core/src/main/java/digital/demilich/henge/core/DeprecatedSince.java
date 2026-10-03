package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @ModularService} interface method as optional to implement from {@link #value()}
 * onward — an implementation may keep providing it past that point if it wants to, but isn't
 * required to.
 *
 * <p>Purely compile-time metadata, read by the {@code modular-processor} annotation processor: it
 * generates a companion {@code {Interface}Skeleton} abstract class providing a throwing
 * implementation of this method, so a {@code @ServiceVersion} implementation whose version is at
 * or after {@link #value()} doesn't have to implement it. Has no effect at runtime; nothing reads
 * this reflectively.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface DeprecatedSince {

    /**
     * The version this method becomes optional at. Must be a semantic version modular-processor
     * can order against the version declared by {@code @ServiceVersion} implementations of this
     * interface — bare integers ("1", "2") are coerced to "1.0.0"/"2.0.0" and compare as expected,
     * full "major.minor.patch" strings work too.
     */
    String value();
}
