package digital.demilich.henge.core;

/**
 * What a channel method returns: called for what the client sends, and for the channel closing. A method
 * on a {@code @HengeService} interface that returns a {@code ChannelHandler} opens a channel instead of
 * answering a call:
 *
 * <pre>{@code
 * ChannelHandler watch(String orderId, Channel toClient);
 * }</pre>
 *
 * <p>The method runs when the channel opens, may start sending through {@code toClient}, and returns the
 * handler. Throwing refuses the channel. Frames are opaque to Henge. Frames reach one handler in the order the
 * client sent them.
 */
public interface ChannelHandler {

    default void onText(String text) { }

    default void onBinary(byte[] data) { }

    /** The channel closed, from either end or because the service retired; called once, last. */
    default void onClose(CloseStatus status) { }
}
