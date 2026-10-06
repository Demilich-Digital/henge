package digital.demilich.henge.spring;

import digital.demilich.henge.core.Channel;
import digital.demilich.henge.core.ChannelHandler;
import digital.demilich.henge.core.CloseStatus;
import digital.demilich.henge.core.ErrorStatus;
import digital.demilich.henge.core.StoreUnavailableException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The glue between a client's websocket and a channel method of a service, so the handler an application
 * registers is a few lines:
 *
 * <pre>{@code
 * registry.addHandler(
 *     ClientChannels.bridge((session, toClient) -> orders.watch(orderIdFrom(session), toClient)),
 *     "/ws/orders/{id}");
 * }</pre>
 *
 * <p>For each client connection it builds the {@link Channel} the service sends to the client through,
 * calls the function to open the channel, and pipes the client's frames to the handler that comes back. A
 * close from either end closes the other with the same status. A client that falls behind is closed with
 * {@link CloseStatus#TRY_AGAIN_LATER}. Authenticating the end user is the application's concern, on its own
 * endpoint; whatever identity the service needs is passed as ordinary arguments of the open.
 *
 * <p>Needs {@code spring-websocket} on the classpath.
 */
public final class ClientChannels {

    /** How long one send to a client may take before the connection is dropped. */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    /** Bytes that may be queued for a client that isn't reading before its channel is closed. */
    private static final int BUFFER_LIMIT_BYTES = 512 * 1024;

    private static final Log log = LogFactory.getLog(ClientChannels.class);

    private ClientChannels() {
    }

    /** Opens the channel for one client connection: normally a call to a service's channel method. */
    @FunctionalInterface
    public interface Opener {

        ChannelHandler open(WebSocketSession session, Channel toClient) throws Exception;
    }

    public static WebSocketHandler bridge(Opener opener) {
        return new Bridge(opener);
    }

    /** The close status a failed open becomes: see the table in {@code docs/design/channels.md}. */
    static CloseStatus closeStatusFor(Throwable failure) {
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof StoreUnavailableException) {
            return new CloseStatus(CloseStatus.TRY_AGAIN_LATER, reason);
        }
        if (failure.getClass().getAnnotation(ErrorStatus.class) != null) {
            return CloseStatus.forErrorStatus(HengeDispatcherController.statusFor(failure).value(), reason);
        }
        return new CloseStatus(CloseStatus.SERVER_ERROR, reason);
    }

    private static final class Bridge extends AbstractWebSocketHandler {

        private static final String CONNECTION = Bridge.class.getName() + ".connection";

        private final Opener opener;

        Bridge(Opener opener) {
            this.opener = opener;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(
                    session, SEND_TIME_LIMIT_MILLIS, 2 * BUFFER_LIMIT_BYTES));
            session.getAttributes().put(CONNECTION, connection);
            try {
                connection.handler = opener.open(session, connection);
            } catch (Exception e) {
                if (!(e instanceof RuntimeException)) {
                    log.warn("Opening a channel failed", e);
                }
                connection.close(closeStatusFor(e));
                return;
            }
            if (connection.handler == null) {
                connection.close(new CloseStatus(CloseStatus.SERVER_ERROR, "the channel method returned no handler"));
            }
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            Connection connection = connection(session);
            if (connection != null && connection.handler != null) {
                connection.handler.onText(message.getPayload());
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            Connection connection = connection(session);
            if (connection != null && connection.handler != null) {
                byte[] data = new byte[message.getPayloadLength()];
                message.getPayload().get(data);
                connection.handler.onBinary(data);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.debug("A client's websocket failed", exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
            Connection connection = connection(session);
            if (connection != null && connection.closed.compareAndSet(false, true) && connection.handler != null) {
                connection.handler.onClose(new CloseStatus(status.getCode(), status.getReason()));
            }
        }

        private static Connection connection(WebSocketSession session) {
            return (Connection) session.getAttributes().get(CONNECTION);
        }
    }

    /** The service's way to the client: a {@link Channel} over the client's session. */
    private static final class Connection implements Channel {

        private final ConcurrentWebSocketSessionDecorator session;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ChannelHandler handler;

        Connection(ConcurrentWebSocketSessionDecorator session) {
            this.session = session;
        }

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public boolean isOpen() {
            return !closed.get() && session.isOpen();
        }

        @Override
        public void sendText(String text) {
            send(new TextMessage(text), text.length());
        }

        @Override
        public void sendBinary(byte[] data) {
            send(new BinaryMessage(data), data.length);
        }

        private void send(org.springframework.web.socket.WebSocketMessage<?> message, int size) {
            if (!isOpen()) {
                return;
            }
            if (session.getBufferSize() + size > BUFFER_LIMIT_BYTES) {
                close(new CloseStatus(CloseStatus.TRY_AGAIN_LATER, "client is not keeping up"));
                return;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                log.debug("Sending to a client failed", e);
                close(new CloseStatus(CloseStatus.SERVER_ERROR, "send failed"));
            }
        }

        @Override
        public void close(CloseStatus status) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                session.close(new org.springframework.web.socket.CloseStatus(status.code(), status.reason()));
            } catch (IOException | RuntimeException e) {
                log.debug("Closing a client's websocket failed", e);
            }
            ChannelHandler current = handler;
            if (current != null) {
                current.onClose(status);
            }
        }
    }
}
