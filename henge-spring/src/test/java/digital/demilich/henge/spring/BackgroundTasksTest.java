package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;

class BackgroundTasksTest {

    private final List<Object> published = new ArrayList<>();

    @Test
    void aTaskThatThrowsAnExceptionRunsAgainOnSchedule() throws Exception {
        var runs = new AtomicInteger();
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            executor.scheduleWithFixedDelay(BackgroundTasks.surviving("test task", () -> {
                runs.incrementAndGet();
                throw new IllegalStateException("a bug outside the body's own catches");
            }, LogFactory.getLog(getClass()), () -> published::add), 0, 5, TimeUnit.MILLISECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runs.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(runs.get()).isGreaterThanOrEqualTo(3);
        assertThat(published).isEmpty();
    }

    @Test
    void aTaskThatThrowsAnErrorStopsAndSaysItDied() throws Exception {
        var runs = new AtomicInteger();
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            executor.scheduleWithFixedDelay(BackgroundTasks.surviving("test task", () -> {
                runs.incrementAndGet();
                throw new AssertionError("fatal");
            }, LogFactory.getLog(getClass()), () -> published::add), 0, 5, TimeUnit.MILLISECONDS);
            Thread.sleep(100);
        } finally {
            executor.shutdownNow();
        }

        assertThat(runs).hasValue(1);
        assertThat(published).singleElement().isInstanceOfSatisfying(BackgroundTasks.Died.class,
                died -> assertThat(died.task()).isEqualTo("test task"));
    }

    @Test
    void aTaskThatDiesOfAnErrorWithNobodyToTellRunsAgain() throws Exception {
        var runs = new AtomicInteger();
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            executor.scheduleWithFixedDelay(BackgroundTasks.surviving("test task", () -> {
                runs.incrementAndGet();
                throw new AssertionError("fatal");
            }, LogFactory.getLog(getClass()), () -> null), 0, 5, TimeUnit.MILLISECONDS);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runs.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(runs.get()).isGreaterThanOrEqualTo(3);
    }
}
