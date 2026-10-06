package digital.demilich.henge.spring;

import java.util.ArrayDeque;
import java.util.Queue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * What a channel's inbound frames wait in: a bounded queue, drained in order by one virtual thread at a
 * time (started when something is queued, ended when it is empty). A slow handler therefore stalls only
 * its own channel, never the trunk's receive thread, and one channel's frames are never reordered or run
 * concurrently. There is no thread per idle channel.
 */
final class Mailbox {

    private static final Log log = LogFactory.getLog(Mailbox.class);

    private final int capacity;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean draining;

    Mailbox(int capacity) {
        this.capacity = capacity;
    }

    /** Queues {@code task}; false if the mailbox is full, in which case the caller closes the channel. */
    boolean offer(Runnable task) {
        synchronized (this) {
            if (tasks.size() >= capacity) {
                return false;
            }
            tasks.add(task);
            if (draining) {
                return true;
            }
            draining = true;
        }
        Thread.ofVirtual().start(this::drain);
        return true;
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = tasks.poll();
                if (task == null) {
                    draining = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("A channel's frame handler threw", e);
            }
        }
    }
}
