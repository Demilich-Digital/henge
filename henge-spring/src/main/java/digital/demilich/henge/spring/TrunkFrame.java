package digital.demilich.henge.spring;

import digital.demilich.henge.core.CloseStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One frame on a trunk: {@code [type: 1 byte][channel: 8 bytes, big-endian][payload: the rest]}, carried
 * in a binary websocket message. The header is binary so a frame in flight is forwarded without parsing
 * it. See "The trunk" in {@code docs/design/channels.md}.
 */
record TrunkFrame(byte type, long channel, byte[] payload) {

    /** Frontend to backend: the payload is the JSON of an {@link OpenRequest}. */
    static final byte OPEN = 1;
    static final byte TEXT = 2;
    static final byte BINARY = 3;
    /** Both ways: two bytes of status code, then the UTF-8 reason. */
    static final byte CLOSE = 4;

    static final int HEADER_BYTES = 9;

    /** Where a backend serves its trunk, after {@code henge.server.path-prefix}. */
    static final String PATH = "/_trunk";

    static TrunkFrame text(long channel, String text) {
        return new TrunkFrame(TEXT, channel, text.getBytes(StandardCharsets.UTF_8));
    }

    static TrunkFrame binary(long channel, byte[] data) {
        return new TrunkFrame(BINARY, channel, data);
    }

    static TrunkFrame close(long channel, CloseStatus status) {
        byte[] reason = status.reason().getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(2 + reason.length);
        payload.putShort((short) status.code()).put(reason);
        return new TrunkFrame(CLOSE, channel, payload.array());
    }

    byte[] encode() {
        return ByteBuffer.allocate(HEADER_BYTES + payload.length).put(type).putLong(channel).put(payload).array();
    }

    /** @throws IllegalArgumentException if the bytes aren't a frame */
    static TrunkFrame decode(ByteBuffer message) {
        if (message.remaining() < HEADER_BYTES) {
            throw new IllegalArgumentException("a trunk frame has at least " + HEADER_BYTES + " bytes, got " + message.remaining());
        }
        byte type = message.get();
        if (type < OPEN || type > CLOSE) {
            throw new IllegalArgumentException("unknown trunk frame type " + type);
        }
        long channel = message.getLong();
        byte[] payload = new byte[message.remaining()];
        message.get(payload);
        return new TrunkFrame(type, channel, payload);
    }

    String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** The status in a {@link #CLOSE} frame. */
    CloseStatus closeStatus() {
        if (payload.length < 2) {
            return new CloseStatus(CloseStatus.SERVER_ERROR, "close frame without a status");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int code = buffer.getShort() & 0xFFFF;
        return new CloseStatus(code, new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8));
    }
}
