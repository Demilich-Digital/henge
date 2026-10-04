package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link ResourceProvider}: this class builds the resource behind the lease it names, once per node,
 * for every service that asks for it. There is at most one provider per lease name.
 *
 * <p>A lease needs no provider. Without one, a service takes a {@link Lease} parameter and builds its own
 * resource, sized from it; with one, a service takes the resource itself (see {@link RequiresLease}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LeasedResource {

    /** The lease's name: lowercase kebab case, as it appears in {@code modular.leases.<name>}. */
    String value();
}
