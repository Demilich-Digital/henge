package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * On a {@link RateLimiter} constructor parameter or field of any bean: the cluster-wide limiter named
 * {@link #value()}, whose constants are {@code henge.rate-limits.<name>.permits}, {@code .period} and
 * {@code .capacity}. Every node that configures the name draws on the same bucket, so the configuration
 * is the limit, and every node has to agree on it.
 *
 * <pre>{@code
 * public NotificationServiceImpl(@RateLimited("notifications") RateLimiter limiter) { ... }
 * }</pre>
 *
 * <p>Unlike a lease, a limiter gates no construction: it is a plain bean, so a controller can take one as
 * well as a service. What a refused call does (fail, drop, queue) is the caller's to decide.
 *
 * <p>Meta-annotated with {@code @Qualifier}, so Spring's own autowiring picks the limiter by name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Qualifier
public @interface RateLimited {

    /** The limiter's name: lowercase kebab case, as it appears in {@code henge.rate-limits.<name>}. */
    String value();
}
