package digital.demilich.henge.core;

/**
 * The backend's handle on the client at the other end of a channel: send to it, or close it. Supplied by
 * Henge as the final parameter of a channel method (see {@link ChannelHandler}), on whichever side the
 * implementation runs. Safe to use from any thread.
 *
 * <p>A send on a channel that is closed is ignored, and a send that overflows the channel's queue closes
 * the channel with {@link CloseStatus#TRY_AGAIN_LATER}.
 */
public interface Channel {

    /** Unique among the channels of the process that hosts the implementation. */
    String id();

    boolean isOpen();

    void sendText(String text);

    void sendBinary(byte[] data);

    /** Closes the channel; closing one that is already closed does nothing. */
    void close(CloseStatus status);
}
