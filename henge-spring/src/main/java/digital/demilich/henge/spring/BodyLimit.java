package digital.demilich.henge.spring;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The bound {@code henge.transport.max-body-bytes} puts on the body of one call, in either direction:
 * reading stops, and says so, as soon as the body is one byte over, so an oversized body is never
 * buffered whole.
 */
final class BodyLimit {

    static final String PROPERTY = "henge.transport.max-body-bytes";

    /** 10 MiB: about 7.5 MiB of binary once base64'd, and what a service call is meant to carry. */
    static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    private BodyLimit() {
    }

    /** Thrown by a stream that has delivered more than its limit. */
    static final class Exceeded extends IOException {
        Exceeded(int max) {
            super("body is over " + PROPERTY + " (" + describe(max) + ")", null);
        }
    }

    /** A view of {@code in} that throws {@link Exceeded} when more than {@code max} bytes are read from it. */
    static InputStream limit(InputStream in, int max) {
        return new FilterInputStream(in) {
            private long read;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    count(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) {
                    count(n);
                }
                return n;
            }

            private void count(int n) throws Exceeded {
                read += n;
                if (read > max) {
                    throw new Exceeded(max);
                }
            }
        };
    }

    /** All of {@code in}; {@link Exceeded} if it holds more than {@code max} bytes. */
    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        limit(in, max).transferTo(out);
        return out.toByteArray();
    }

    /** {@code 10 MiB} for a whole number of MiB, else bytes. */
    static String describe(long bytes) {
        long mib = 1024 * 1024;
        return bytes >= mib && bytes % mib == 0 ? bytes / mib + " MiB" : bytes + " bytes";
    }
}
