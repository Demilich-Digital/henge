package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a Spring {@code @Scheduled} method (or its class), or on a {@code SchedulingConfigurer}: the author
 * knows this runs on <em>every node</em> that hosts the bean, once each, and that is what they want.
 *
 * <p>Spring's scheduling is per process, so with three replicas a {@code @Scheduled} method runs three times,
 * which is rarely intended and never visible in development, where there is one process. Henge refuses it
 * unless this is on it. For a job that should run once across the cluster, use {@link HengeScheduled}
 * instead. The right uses of this are the ones that really are per node: evicting a local cache, flushing
 * local metrics.
 *
 * <p>The name is the point: the person who writes it has typed the consequence. It changes nothing else, and
 * does nothing on its own. See {@code docs/design/scheduling.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface HengeAcknowledgeThisRunsOnEveryNode {
}
