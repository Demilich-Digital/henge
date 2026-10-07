package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ImmutableBytesTest {

    @Test
    void copyOfIsUnaffectedByLaterMutationOfTheSource() {
        byte[] source = {1, 2, 3};
        ImmutableBytes bytes = ImmutableBytes.copyOf(source);

        source[0] = 99;

        assertThat(bytes.get(0)).isEqualTo((byte) 1);
    }

    @Test
    void toByteArrayHandsBackAFreshCopy() {
        ImmutableBytes bytes = ImmutableBytes.of(1, 2, 3);

        bytes.toByteArray()[0] = 99;

        assertThat(bytes.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void ofNarrowsEachValueLikeACast() {
        assertThat(ImmutableBytes.of(0xCA, 0xFE, -1).toByteArray())
                .containsExactly((byte) 0xCA, (byte) 0xFE, (byte) 0xFF);
    }

    @Test
    void copyOfARangeTakesJustThatRange() {
        assertThat(ImmutableBytes.copyOf(new byte[] {1, 2, 3, 4}, 1, 2).toByteArray()).containsExactly(2, 3);
        assertThatThrownBy(() -> ImmutableBytes.copyOf(new byte[2], 1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ImmutableBytes.copyOf(new byte[2], -1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void copyOfABufferTakesTheRemainingBytesAndLeavesItsPosition() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        buffer.get();

        assertThat(ImmutableBytes.copyOf(buffer).toByteArray()).containsExactly(2, 3, 4);
        assertThat(buffer.position()).isEqualTo(1);
    }

    @Test
    void equalsAndHashCodeFollowTheContentNotTheBackingArray() {
        ImmutableBytes whole = ImmutableBytes.of(9, 1, 2, 9);

        assertThat(whole.slice(1, 3)).isEqualTo(ImmutableBytes.of(1, 2));
        assertThat(whole.slice(1, 3).hashCode()).isEqualTo(ImmutableBytes.of(1, 2).hashCode());
        assertThat(ImmutableBytes.of(1, 2)).isNotEqualTo(ImmutableBytes.of(1, 3)).isNotEqualTo(ImmutableBytes.of(1));
        assertThat(ImmutableBytes.of()).isEqualTo(ImmutableBytes.copyOf(new byte[0]));
    }

    @Test
    void aRecordComponentComparesByContent() {
        record Sheet(String name, ImmutableBytes png) {
        }

        assertThat(new Sheet("a", ImmutableBytes.of(1, 2))).isEqualTo(new Sheet("a", ImmutableBytes.copyOf(new byte[] {1, 2})));
    }

    @Test
    void sliceValidatesItsBounds() {
        ImmutableBytes bytes = ImmutableBytes.of(1, 2, 3);

        assertThat(bytes.slice(0, 3)).isEqualTo(bytes);
        assertThat(bytes.slice(2, 2).isEmpty()).isTrue();
        assertThatThrownBy(() -> bytes.slice(2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bytes.slice(0, 4)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bytes.slice(-1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void sliceOfASliceIsRelativeToTheSlice() {
        assertThat(ImmutableBytes.of(0, 1, 2, 3, 4).slice(1, 4).slice(1, 2)).isEqualTo(ImmutableBytes.of(2));
    }

    @Test
    void getRejectsAnIndexOutsideTheSlice() {
        ImmutableBytes slice = ImmutableBytes.of(1, 2, 3, 4).slice(1, 3);

        assertThat(slice.get(0)).isEqualTo((byte) 2);
        assertThatThrownBy(() -> slice.get(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> slice.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theByteBufferViewIsReadOnlyAndCoversTheSlice() {
        ByteBuffer view = ImmutableBytes.of(1, 2, 3, 4).slice(1, 3).asByteBuffer();

        assertThat(view.isReadOnly()).isTrue();
        assertThat(view.remaining()).isEqualTo(2);
        assertThat(view.get()).isEqualTo((byte) 2);
        assertThatThrownBy(() -> view.put((byte) 0)).isInstanceOf(java.nio.ReadOnlyBufferException.class);
    }

    @Test
    void theInputStreamReadsTheSlice() throws IOException {
        assertThat(ImmutableBytes.of(1, 2, 3, 4).slice(1, 3).asInputStream().readAllBytes()).containsExactly(2, 3);
    }

    @Test
    void comparesLexicographicallyAsUnsignedBytes() {
        assertThat(ImmutableBytes.of(1, 2)).isLessThan(ImmutableBytes.of(1, 3));
        assertThat(ImmutableBytes.of(1)).isLessThan(ImmutableBytes.of(1, 0));
        assertThat(ImmutableBytes.of(0x80)).isGreaterThan(ImmutableBytes.of(0x7F));
    }

    @Test
    void toStringShowsTheLengthAndAShortPrefixOnly() {
        assertThat(ImmutableBytes.of().toString()).isEqualTo("ImmutableBytes[0 bytes]");
        assertThat(ImmutableBytes.of(0xCA, 0xFE).toString()).isEqualTo("ImmutableBytes[2 bytes: cafe]");
        assertThat(ImmutableBytes.copyOf(new byte[100]).toString()).isEqualTo("ImmutableBytes[100 bytes: 0000000000000000...]");
    }
}
