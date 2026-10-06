package digital.demilich.henge.core;

/**
 * Why a channel closed: a WebSocket close code, so clients and browsers understand it, and a reason.
 * Reconnecting is always the client's choice, and always correct.
 *
 * @param code a WebSocket close code
 * @param reason free text; a close frame carries at most 123 bytes of it, so it is truncated there
 */
public record CloseStatus(int code, String reason) {

    /** Normal close, by either end. */
    public static final CloseStatus NORMAL = new CloseStatus(1000, "");
    /** A frame larger than {@code henge.channels.max-frame-bytes}. */
    public static final int TOO_BIG = 1009;
    /** Unexpected failure: the backend threw, or the connection to it was lost. */
    public static final int SERVER_ERROR = 1011;
    /** The backend is retiring this service: reconnect, and you land elsewhere. */
    public static final int SERVICE_RESTART = 1012;
    /** A queue overflowed, or no backend could be found or asked. */
    public static final int TRY_AGAIN_LATER = 1013;

    private static final int MAX_REASON_BYTES = 123;

    public CloseStatus {
        reason = truncate(reason == null ? "" : reason);
    }

    /** An open call that threw an exception annotated {@code @ErrorStatus(status)} closes with {@code 4000 + status}. */
    public static CloseStatus forErrorStatus(int httpStatus, String reason) {
        return new CloseStatus(4000 + httpStatus, reason);
    }

    private static String truncate(String reason) {
        byte[] bytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_REASON_BYTES) {
            return reason;
        }
        int end = MAX_REASON_BYTES;
        // Never cut inside a multi-byte character.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
