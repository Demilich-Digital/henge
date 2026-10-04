package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link ServiceVersion} implementation's constructor parameter: this service needs a share of a
 * scarce, cluster-wide resource, such as a database that accepts 200 connections however many nodes
 * host the service. Henge acquires every lease a service's constructor names before it constructs the
 * implementation, so a node that can't get one never constructs it (and so never opens the resource),
 * and the service is reached remotely instead.
 *
 * <p>The name is the resource's: its cluster-wide capacity is {@code modular.leases.orders-db.capacity},
 * and what one node claims is {@code modular.leases.orders-db.amount}. A node claims a lease once,
 * however many of its services and versions declare it; they share the claim.
 *
 * <p>What the parameter receives depends on its type:
 * <ul>
 *   <li>{@link Lease}: the name and the per-node amount. The service builds its own resource from it,
 *       which is the explicit way to go without a provider; sizing it from {@code amount()} is then
 *       the service's job.
 *   <li>anything else: the resource built by the lease's {@link ResourceProvider}, shared by every service
 *       on the node that asks for it. The lease must have a provider, and its type must fit the parameter.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface RequiresLease {

    /** The lease's name: lowercase kebab case, as it appears in {@code modular.leases.<name>}. */
    String value();
}
