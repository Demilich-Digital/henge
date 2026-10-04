package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link ServiceVersion} implementation: this service needs a share of a scarce, cluster-wide
 * resource, such as a database that accepts 200 connections however many nodes host the service.
 * Henge acquires every declared lease before it constructs the implementation, so a node that can't
 * get one never constructs it (and so never opens the resource), and the service is reached
 * remotely instead.
 *
 * <p>{@code @RequiresLease("orders-db")} names the resource; its cluster-wide capacity is
 * {@code modular.leases.orders-db.capacity}, and what one node claims is
 * {@code modular.leases.orders-db.amount}, shared by every service on the node that declares it. The constructor receives what was granted
 * as a {@link Lease} parameter. With one declared lease any {@code Lease} parameter is it; with
 * several, mark each parameter {@code @RequiresLease("orders-db")} to say which.
 *
 * <p>The resource must belong to the service: created by the implementation (or by something that
 * is given its {@link Lease}), never a bean shared with the rest of the application, which would
 * open its connections whether or not the service was built here.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PARAMETER})
@Repeatable(RequiresLeases.class)
public @interface RequiresLease {

    /** The resource's name: lowercase kebab case, as it appears in {@code modular.leases.<name>}. */
    String value();
}
