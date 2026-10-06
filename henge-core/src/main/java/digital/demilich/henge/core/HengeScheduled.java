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

    /** What {@link #maxRuntime()} is when it isn't set. */
    String DEFAULT_MAX_RUNTIME = "1h";

    /**
     * The longest one run may take: milliseconds, a unit suffix ({@code 90m}, {@code 2h}) or ISO-8601
     * ({@code PT2H}); one hour if empty. Past it the run is interrupted and stops being counted as running,
     * so the next fire may start, even if the method ignored the interrupt and is still going. It is what
     * keeps a hung run, whose thread would otherwise report itself alive forever, from blocking every future
     * run. Placeholders are resolved.
     *
     * <p><b>A run lives on one node, and is lost with it.</b> If that node dies, the run is not resumed
     * anywhere: the next fire starts again from nothing, which for a job that takes hours or days is the next
     * day. Henge has no durable store to resume from, on purpose. A long job has to be written as batches,
     * recording its own progress in a database of yours and safe to repeat, so that any run, on any node,
     * picks up where the last one stopped.
     */
    String maxRuntime() default "";

    /**
     * Whether a fire may start while the previous run is still going, on any node. False by default: a fire
     * that finds the job still running is skipped, not queued, and logged with the node that is running it.
     * A node that stops being the one running it (it was cut off from the store for longer than the claim
     * lasts, and another node was given it) has its run interrupted when it notices.
     *
     * <p>With overlap on, each run still holds a claim of its own, named for the fire it belongs to: it shows
     * where the run is going, it is renewed and cut off at {@link #maxRuntime()} like any other, and it
     * refuses a second node that won the same fire.
     */
    boolean overlap() default false;
}
