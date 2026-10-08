package digital.demilich.henge.spring;

import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Keeps Henge's scheduled background tasks (the lease heartbeat and poll, the advertisement refresh, the rate
 * limiters' membership) from stopping without a sound. {@code scheduleWithFixedDelay} cancels every later run
 * of a task that throws, and keeps what it threw in a future nobody reads. Each body already catches what it
 * expects, around every store call; what is left is a bug outside those catches, or an {@link Error}.
 *
 * <p>A {@link RuntimeException} is logged and the run ends, so the next one still happens. An {@link Error} is
 * fatal on purpose: a process whose heartbeat has stopped keeps hosting on claims that lapse, which nothing
 * heals but a restart. It is logged, {@link Died} is published (with the Boot starter, the process then
 * reports {@code LivenessState.BROKEN}, so the orchestrator restarts it), and the task stops.
 */
final class BackgroundTasks {

    private BackgroundTasks() {
    }

    /** Published when a background task dies of an {@link Error}: the process should be restarted. */
    static final class Died extends ApplicationEvent {

        private final String task;

        Died(String task, Error cause) {
            super(cause);
            this.task = task;
        }

        String task() {
            return task;
        }
    }

    /**
     * {@code body}, as a scheduled task that survives a {@link RuntimeException} and dies of an {@link Error}.
     *
     * @param publisher where to publish {@link Died}; what it supplies may be null, outside a context
     */
    static Runnable surviving(String task, Runnable body, Log log, Supplier<ApplicationEventPublisher> publisher) {
        return () -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                log.error("Henge's " + task + " failed unexpectedly; it runs again on schedule", e);
            } catch (Error e) {
                log.error("Henge's " + task + " died, and has stopped. This process can no longer keep its place in the "
                        + "cluster, and should be restarted", e);
                ApplicationEventPublisher events = publisher.get();
                if (events != null) {
                    try {
                        events.publishEvent(new Died(task, e));
                    } catch (RuntimeException publishing) {
                        log.error("Saying that Henge's " + task + " died failed", publishing);
                    }
                }
                throw e;
            }
        };
    }
}
