package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@code @Bean} method that makes a {@code DataSource}, or on the application's {@code @Configuration}
 * class (for the one Spring Boot builds from {@code spring.datasource.*}): the author knows this opens its own
 * connection pool on <em>every process</em> that runs the application, and that is what they want.
 *
 * <p>A pool that is an ordinary bean exists before any lease could be asked for, and JPA, Flyway and schema
 * initialization connect to it as the context starts, so the database sees pools times processes
 * connections. Henge refuses it unless this is on it. To cap the cluster's share of the database instead,
 * build the pool in a {@link LeasedResource} and ask for it with {@link RequiresLease}. The right uses of this
 * are the ones with nothing to cap: an embedded database, or one that takes any number of connections.
 *
 * <p>The name is the point: the person who writes it has typed the consequence. It changes nothing else, and
 * does nothing on its own.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface HengeAcknowledgeThisOpensAPoolOnEveryNode {
}
