package digital.demilich.henge.spring;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * One open channel as the service that hosts it sees it: the {@link Channel} the implementation is given
 * and the {@link ChannelHandler} its caller gets back, both wrapped, so the binding knows every open
 * channel (to close them when the service retires) and the handler's {@code onClose} runs exactly once,
 * whichever end closes first.
 */
final class ChannelSession {

    private static final Log log = LogFactory.getLog(ChannelSession.class);

    private final Channel client;
    private final Runnable onEnded;

    private ChannelHandler handler;
    private CloseStatus closedWith;
    private boolean notified;

    ChannelSession(Channel client, Runnable onEnded) {
        this.client = client;
        this.onEnded = onEnded;
    }

    /** What the implementation gets as its {@code Channel}: the client's, until either end closes it. */
    Channel toClient() {
        return new Channel() {
            @Override
            public String id() {
                return client.id();
            }

            @Override
            public boolean isOpen() {
                return !isClosed() && client.isOpen();
            }

            @Override
            public void sendText(String text) {
                if (!isClosed()) {
                    client.sendText(text);
                }
            }

            @Override
            public void sendBinary(byte[] data) {
                if (!isClosed()) {
                    client.sendBinary(data);
                }
            }

            @Override
            public void close(CloseStatus status) {
                if (end(status)) {
                    client.close(status);
                    deliverClose();
                }
            }
        };
    }

    /**
     * The handler the opener of the channel gets back. A close from that side only ends the session: the
     * client already knows, so it isn't told again.
     */
    ChannelHandler attach(ChannelHandler returned) {
        synchronized (this) {
            handler = returned;
        }
        // The implementation closed the channel before it had returned the handler.
        deliverClose();
        return new ChannelHandler() {
            @Override
            public void onText(String text) {
                if (!isClosed()) {
                    returned.onText(text);
                }
            }

            @Override
            public void onBinary(byte[] data) {
                if (!isClosed()) {
                    returned.onBinary(data);
                }
            }

            @Override
            public void onClose(CloseStatus status) {
                if (end(status)) {
                    deliverClose();
                }
            }
        };
    }

    /** The open call threw: there is no channel. */
    void abandon() {
        end(CloseStatus.NORMAL);
        synchronized (this) {
            notified = true;
        }
    }

    /** Closes the channel from this side, telling the client and the handler. */
    void close(CloseStatus status) {
        toClient().close(status);
    }

    private synchronized boolean isClosed() {
        return closedWith != null;
    }

    private boolean end(CloseStatus status) {
        synchronized (this) {
            if (closedWith != null) {
                return false;
            }
            closedWith = status;
        }
        onEnded.run();
        return true;
    }

    private void deliverClose() {
        ChannelHandler target;
        CloseStatus status;
        synchronized (this) {
            if (closedWith == null || handler == null || notified) {
                return;
            }
            notified = true;
            target = handler;
            status = closedWith;
        }
        try {
            target.onClose(status);
        } catch (RuntimeException e) {
            log.warn("A channel handler threw from onClose", e);
        }
    }
}
