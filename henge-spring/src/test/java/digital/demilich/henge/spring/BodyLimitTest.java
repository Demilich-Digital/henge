package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class BodyLimitTest {

    @Test
    void aBodyAtTheLimitIsReadWhole() throws IOException {
        assertThat(BodyLimit.readAll(new ByteArrayInputStream(new byte[100]), 100)).hasSize(100);
        assertThat(BodyLimit.readAll(new ByteArrayInputStream(new byte[0]), 100)).isEmpty();
    }

    @Test
    void aBodyOneByteOverIsRefused() {
        assertThatThrownBy(() -> BodyLimit.readAll(new ByteArrayInputStream(new byte[101]), 100))
                .isInstanceOf(BodyLimit.Exceeded.class)
                .hasMessageContaining("henge.transport.max-body-bytes (100 bytes)");
    }

    @Test
    void readingStopsAtTheLimitInsteadOfDrainingTheStream() {
        long[] served = {0};
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                served[0]++;
                return 1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                served[0] += length;
                return length;
            }
        };

        assertThatThrownBy(() -> BodyLimit.readAll(endless, 1000)).isInstanceOf(BodyLimit.Exceeded.class);
        assertThat(served[0]).isLessThan(1_000_000);
    }

    @Test
    void singleByteReadsAreCountedToo() throws IOException {
        InputStream limited = BodyLimit.limit(new ByteArrayInputStream(new byte[3]), 2);

        limited.read();
        limited.read();
        assertThatThrownBy(limited::read).isInstanceOf(BodyLimit.Exceeded.class);
    }

    @Test
    void describeSaysMebibytesForAWholeNumberOfThemElseBytes() {
        assertThat(BodyLimit.describe(10 * 1024 * 1024)).isEqualTo("10 MiB");
        assertThat(BodyLimit.describe(10 * 1024 * 1024 + 1)).isEqualTo("10485761 bytes");
        assertThat(BodyLimit.describe(512)).isEqualTo("512 bytes");
    }
}
