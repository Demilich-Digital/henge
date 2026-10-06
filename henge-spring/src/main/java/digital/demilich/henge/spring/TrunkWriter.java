package digital.demilich.henge.spring;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The one writer of a trunk: one send in flight at a time, taking a frame from each channel with
 * something to send in turn, so a channel that sends a lot can't starve the others. Each channel has its
 * own bounded {@link Lane}; a full lane is the channel's overflow, not the trunk's.
 */
final class TrunkWriter {

    private static final Log log = LogFactory.getLog(TrunkWriter.class);

    /** Sends one encoded frame, returning when it has been handed to the connection. */
    @FunctionalInterface
    interface Sender {

        void send(byte[] frame) throws Exception;
    }

    /**
     * Not frames, but things a trunk's connection sends that must share its one send in flight: the
     * {@link Sender} sees these two arrays (by identity) where it would see a frame. A frame is never empty.
     */
    static final byte[] PING = new byte[0];
    static final byte[] CLOSE_TRUNK = new byte[1];

    private final Sender sender;
    private final Runnable onFailure;
    private final LinkedBlockingQueue<Lane> ready = new LinkedBlockingQueue<>();
    private final Lane control = new Lane(Integer.MAX_VALUE);
    private final Thread thread;
    private volatile boolean stopped;

    /** @param onFailure run once, if a send fails: the trunk is gone */
    TrunkWriter(Sender sender, Runnable onFailure) {
        this.sender = sender;
        this.onFailure = onFailure;
        this.thread = Thread.ofVirtual().name("henge-trunk-writer").start(this::run);
    }

    /** A channel's place in the queue of frames waiting for the trunk. */
    final class Lane {

        private final int capacity;
        private final Queue<byte[]> frames = new ArrayDeque<>();
        private boolean scheduled;

        private Lane(int capacity) {
            this.capacity = capacity;
        }

        /** Queues a frame; false if this channel already has {@code capacity} waiting. */
        boolean offer(TrunkFrame frame) {
            return add(frame, true);
        }

        /** Queues a frame regardless of the bound: the one close a channel ends with. */
        void offerControl(TrunkFrame frame) {
            add(frame, false);
        }

        private boolean add(TrunkFrame frame, boolean bounded) {
            return add(frame.encode(), bounded);
        }

        private boolean add(byte[] encoded, boolean bounded) {
            synchronized (this) {
                if (bounded && frames.size() >= capacity) {
                    return false;
                }
                frames.add(encoded);
                if (scheduled) {
                    return true;
                }
                scheduled = true;
            }
            ready.add(this);
            return true;
        }

        private byte[] next() {
            synchronized (this) {
                return frames.poll();
            }
        }

        /** After a frame was sent: whether this lane still has some, and so goes to the back of the line. */
        private boolean stillWaiting() {
            synchronized (this) {
                if (frames.isEmpty()) {
                    scheduled = false;
                    return false;
                }
                return true;
            }
        }
    }

    Lane lane(int capacity) {
        return new Lane(capacity);
    }

    /** Has the connection send a ping, in its turn. */
    void ping() {
        control.add(PING, false);
    }

    /** Has the connection close, in its turn: after the frames already queued on every lane have gone. */
    void closeTrunk() {
        control.add(CLOSE_TRUNK, false);
    }

    void stop() {
        stopped = true;
        thread.interrupt();
    }

    private void run() {
        try {
            while (!stopped) {
                Lane lane = ready.take();
                byte[] frame = lane.next();
                if (frame != null) {
                    sender.send(frame);
                }
                if (lane.stillWaiting()) {
                    ready.add(lane);
                }
            }
        } catch (InterruptedException e) {
            // stop()
        } catch (Exception e) {
            if (!stopped) {
                log.debug("Sending on a trunk failed", e);
                onFailure.run();
            }
        }
    }
}
