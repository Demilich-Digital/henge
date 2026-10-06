package digital.demilich.henge.spring;

import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Whether this process may take traffic yet. It may not until it has reached the ephemeral store, and
 * finished what could only be done once it had: a service that needs a lease can't say whether it is
 * hosted here or reached elsewhere, and a rate limiter can't say how many nodes share its limit.
 * Each of those registers what it is waiting for ({@link #await}) when the store turns out to be
 * unreachable as it is built, and is retried here until it can be done.
 *
 * <p>So a node started while the store is away starts anyway: alive, listening, and not ready, which is
 * the state an orchestrator leaves alone (it is only a restart it would do to a node that fails to start,
 * and a restart doesn't bring the store back). The starter answers every request with {@code 503} and
 * reports readiness as refusing traffic until {@link #isReady()}. Only this first contact is gated: a
 * node that has been ready stays ready through a later outage and carries on as best it can.
 *
 * <p>Evaluated once as the context finishes starting, before the web server accepts a request, so a
 * node whose store is there is ready before anyone can ask. Otherwise it is retried on a thread of its own.
 */
public class HengeBootGate implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(HengeBootGate.class);

    private static final long RETRY_MILLIS = 500;

    /** What is asked of the store to know it is there; the answer is not used. */
    static final String PROBE_KEY = "boot";

    private record Waiting(String what, BooleanSupplier attempt) {
    }

    private final SystemEphemeralDatastore datastore;
    private final List<Waiting> waiting = new CopyOnWriteArrayList<>();
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    private volatile boolean ready;
    private volatile boolean running;
    private volatile boolean failed;
    private final CountDownLatch becameReady = new CountDownLatch(1);
    private Thread retrying;

    HengeBootGate(SystemEphemeralDatastore datastore) {
        this.datastore = datastore;
    }

    /**
     * Holds the process not ready until {@code attempt} returns true. It is called again after each
     * {@link StoreUnavailableException}, and after each false.
     */
    void await(String what, BooleanSupplier attempt) {
        waiting.add(new Waiting(what, attempt));
    }

    /** Whether this process may take traffic: for a plain-Spring application to ask, in its own health check. */
    public boolean isReady() {
        return ready;
    }

    /** Blocks until the process is ready, which may be never. */
    void awaitReady() throws InterruptedException {
        becameReady.await();
    }

    /** Called once, when the process becomes ready, from whichever thread finds it so. */
    void onReady(Runnable listener) {
        readyListeners.add(listener);
    }

    /** Tries everything still waited for; returns whether the process is now ready. */
    private boolean evaluate() {
        List<String> stillWaiting = new ArrayList<>();
        try {
            datastore.read(PROBE_KEY);
        } catch (StoreUnavailableException e) {
            stillWaiting.add("the ephemeral store");
        }
        if (stillWaiting.isEmpty()) {
            for (Waiting entry : waiting) {
                boolean done;
                try {
                    done = entry.attempt().getAsBoolean();
                } catch (StoreUnavailableException e) {
                    done = false;
                } catch (RuntimeException e) {
                    // Not the store: trying again would only fail the same way, so the process stays
                    // alive, and not ready, for someone to read why.
                    log.error("Giving up on " + entry.what() + ", so this process will not become ready", e);
                    failed = true;
                    return false;
                }
                if (done) {
                    waiting.remove(entry);
                } else {
                    stillWaiting.add(entry.what());
                }
            }
        }
        if (!stillWaiting.isEmpty()) {
            return false;
        }
        ready = true;
        becameReady.countDown();
        return true;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (evaluate()) {
            return;
        }
        log.warn("Can't reach the ephemeral store: not accepting traffic until it can be reached, "
                + "retrying every " + RETRY_MILLIS + " ms (" + waiting.size() + " things waiting on it)");
        retrying = new Thread(this::retry, "henge-boot-gate");
        retrying.setDaemon(true);
        retrying.start();
    }

    private void retry() {
        try {
            while (running && !failed && !evaluate()) {
                Thread.sleep(RETRY_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (ready) {
            log.info("Reached the ephemeral store: accepting traffic");
            readyListeners.forEach(Runnable::run);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (retrying != null) {
            retrying.interrupt();
        }
    }

    /** Early: before the web server accepts a request, and before anything advertises. */
    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
