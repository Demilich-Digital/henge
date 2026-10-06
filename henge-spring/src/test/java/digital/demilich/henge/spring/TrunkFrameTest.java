package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.CloseStatus;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class TrunkFrameTest {

    private static TrunkFrame roundTrip(TrunkFrame frame) {
        return TrunkFrame.decode(ByteBuffer.wrap(frame.encode()));
    }

    @Test
    void aTextFrameKeepsItsChannelAndItsText() {
        TrunkFrame frame = roundTrip(TrunkFrame.text(0x0102030405060708L, "héllo"));

        assertThat(frame.type()).isEqualTo(TrunkFrame.TEXT);
        assertThat(frame.channel()).isEqualTo(0x0102030405060708L);
        assertThat(frame.text()).isEqualTo("héllo");
    }

    @Test
    void theHeaderIsTheTypeThenTheChannelBigEndian() {
        byte[] bytes = TrunkFrame.binary(1, new byte[] {9}).encode();

        assertThat(bytes).containsExactly(TrunkFrame.BINARY, 0, 0, 0, 0, 0, 0, 0, 1, 9);
    }

    @Test
    void aBinaryFrameKeepsItsBytes() {
        assertThat(roundTrip(TrunkFrame.binary(7, new byte[] {1, 2, 3})).payload()).containsExactly(1, 2, 3);
    }

    @Test
    void aCloseFrameCarriesAStatusAndAReason() {
        TrunkFrame frame = roundTrip(TrunkFrame.close(3, new CloseStatus(4404, "no such feed")));

        assertThat(frame.type()).isEqualTo(TrunkFrame.CLOSE);
        assertThat(frame.closeStatus()).isEqualTo(new CloseStatus(4404, "no such feed"));
    }

    @Test
    void aCloseWithoutAReasonIsStillAStatus() {
        assertThat(roundTrip(TrunkFrame.close(3, CloseStatus.NORMAL)).closeStatus().code()).isEqualTo(1000);
    }

    @Test
    void somethingThatIsNotAFrameIsRejected() {
        assertThatThrownBy(() -> TrunkFrame.decode(ByteBuffer.wrap(new byte[3]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrunkFrame.decode(ByteBuffer.wrap(new byte[] {99, 0, 0, 0, 0, 0, 0, 0, 1})))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
