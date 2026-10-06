package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link HengeService} interface: this service is hosted by <em>every process</em>, every version of it,
 * because something about it only works that way. A frontend that holds clients' connections and a local
 * load balancer are the usual cases: a process that reached it over the network would defeat what it is for.
 *
 * <p>By default a process hosts every service, and {@code henge.serve} narrows that, so a service that has to
 * be everywhere is one deployment setting away from not being. Henge refuses to start a process that would
 * reach it remotely: one whose {@code henge.serve} leaves it out, one configured {@code internal-rest}, and
 * one whose implementation waits on a {@link RequiresLease}, which a full lease would refuse. Add the service
 * to {@code henge.serve} (or drop the annotation) to resolve it.
 *
 * <p>It is a statement about placement and nothing more: it doesn't make the service stateless, or its
 * instances aware of each other.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RunOnEveryNode {
}
