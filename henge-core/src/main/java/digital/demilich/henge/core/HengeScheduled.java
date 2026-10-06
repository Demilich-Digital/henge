package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a method of a singleton bean: run it on a cron schedule, <em>once per fire across the whole
 * cluster</em>, however many nodes host the bean. Spring's {@code @Scheduled} runs once per process,
 * so with three replicas it runs three times; this is what to use instead. The method takes no
 * arguments.
 *
 * <p>Every node knows the job and when it is due, because the annotation is in the jar; the nodes
 * only decide between themselves which one runs a given fire, through the ephemeral store. Nothing
 * records that a job ran, so:
 * <ul>
 *   <li>A fire that nobody was up for is skipped, never caught up.
 *   <li>A fire is run at most once in the normal case, but a store that is failing over can let two
 *       nodes both run it. The method must be safe to run twice.
 *   <li>If the store can't be reached when a fire is due, the fire is skipped.
 * </ul>
 *
 * <p>Cron only: {@code fixedRate} and {@code fixedDelay} count from each node's own start, so they have
 * no cluster-wide meaning. See {@code docs/design/scheduling.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HengeScheduled {

    /** A Spring cron expression (six fields: second, minute, hour, day, month, weekday). Placeholders are resolved. */
    String cron();

    /**
     * The time zone {@link #cron()} is read in, e.g. {@code Europe/Paris}; the JVM's default if empty.
     * Every node must read it in the same zone, or they won't agree on when a fire is due and each runs
     * its own: set it explicitly for a cluster that spans zones. Placeholders are resolved.
     */
    String zone() default "";

    /**
     * The job's name, which is what its fires are claimed under. The declaring class and method name
     * if empty, so it is only needed for two beans of one class that both declare the job, or to keep a
     * job's identity across a rename.
     */
    String name() default "";
}
