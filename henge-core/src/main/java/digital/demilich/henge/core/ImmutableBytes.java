package digital.demilich.henge.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * An immutable sequence of bytes -- the {@code @HengeService} boundary type for binary data, where a
 * {@code byte[]} is not allowed: an array can be mutated by whoever holds it, so a caller that kept
 * hold of one it passed (or a callee of one it returned) would see a change embedded that it never
 * could once the service is split. See {@link ImmutableList}, which closes the same gap for collections.
 *
 * <p>Always copies on the way in and on the way out: {@link #copyOf} takes its own copy, and
 * {@link #toByteArray()} hands back a fresh one. {@link #slice} shares the backing array, which is
 * safe because nothing can write to it. Unlike an array, it has content-based {@code equals} and
 * {@code hashCode}, so a record with an {@code ImmutableBytes} component compares as expected.
 *
 * <p>On the wire it is a base64 string, as Jackson writes a {@code byte[]}. The whole call body travels
 * in memory and is bounded by {@code henge.transport.max-body-bytes} (10 MiB by default, counting the
 * base64), so this is for payloads of that scale; a larger one belongs on a channel, which carries
 * binary frames.
 *
 * <p>{@link #toString()} shows only the length and a short prefix, so a secret or a large payload is
 * not written whole into a log line.
 */
public final class ImmutableBytes implements Comparable<ImmutableBytes> {

    private static final ImmutableBytes EMPTY = new ImmutableBytes(new byte[0], 0, 0);
    private static final int TO_STRING_PREFIX = 8;

    private final byte[] bytes;
    private final int offset;
    private final int length;

    private ImmutableBytes(byte[] bytes, int offset, int length) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    public static ImmutableBytes of() {
        return EMPTY;
    }

    /** The given values as bytes, each narrowed as a {@code (byte)} cast would: {@code of(0xCA, 0xFE)}. */
    public static ImmutableBytes of(int... values) {
        byte[] copy = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = (byte) values[i];
        }
        return fromOwned(copy);
    }

    public static ImmutableBytes copyOf(byte[] source) {
        return fromOwned(source.clone());
    }

    /** {@code length} bytes of {@code source} starting at {@code offset}. */
    public static ImmutableBytes copyOf(byte[] source, int offset, int length) {
        return fromOwned(Arrays.copyOfRange(source, offset, checkedEnd(source.length, offset, length)));
    }

    /** The bytes remaining in {@code source}; its position is left where it was. */
    public static ImmutableBytes copyOf(ByteBuffer source) {
        byte[] copy = new byte[source.remaining()];
        source.duplicate().get(copy);
        return fromOwned(copy);
    }

    /** Takes {@code array} without copying: only for an array nothing else holds. */
    private static ImmutableBytes fromOwned(byte[] array) {
        return array.length == 0 ? EMPTY : new ImmutableBytes(array, 0, array.length);
    }

    private static int checkedEnd(int available, int offset, int length) {
        if (offset < 0 || length < 0 || offset > available - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + " out of range for " + available + " bytes");
        }
        return offset + length;
    }

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    /** The byte at {@code index}, as a signed byte. */
    public byte get(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index " + index + " out of range for " + length + " bytes");
        }
        return bytes[offset + index];
    }

    /** The bytes from {@code from} (inclusive) to {@code to} (exclusive); no copy is made. */
    public ImmutableBytes slice(int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException("from " + from + " is after to " + to);
        }
        checkedEnd(length, from, to - from);
        return from == to ? EMPTY : new ImmutableBytes(bytes, offset + from, to - from);
    }

    /** A fresh array, which the caller may do what it likes with. */
    public byte[] toByteArray() {
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    /** A read-only view of the bytes. */
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(bytes, offset, length).asReadOnlyBuffer();
    }

    public InputStream asInputStream() {
        return new ByteArrayInputStream(bytes, offset, length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImmutableBytes that
                && Arrays.equals(bytes, offset, offset + length, that.bytes, that.offset, that.offset + that.length);
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = offset; i < offset + length; i++) {
            hash = 31 * hash + bytes[i];
        }
        return hash;
    }

    /** Lexicographic, comparing bytes as unsigned values. */
    @Override
    public int compareTo(ImmutableBytes that) {
        return Arrays.compareUnsigned(bytes, offset, offset + length, that.bytes, that.offset, that.offset + that.length);
    }

    @Override
    public String toString() {
        int shown = Math.min(length, TO_STRING_PREFIX);
        String prefix = HexFormat.of().formatHex(bytes, offset, offset + shown);
        return "ImmutableBytes[" + length + " bytes" + (length == 0 ? "" : ": " + prefix + (shown < length ? "..." : "")) + "]";
    }
}
