package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrunkWriterTest {

    @Test
    void aFullLaneRefusesAFrameButAControlFrameStillGoes() throws Exception {
        var release = new CountDownLatch(1);
        var sentFirst = new CountDownLatch(1);
        List<TrunkFrame> sent = Collections.synchronizedList(new ArrayList<>());
        var writer = new TrunkWriter(frame -> {
            sentFirst.countDown();
            release.await();
            sent.add(TrunkFrame.decode(java.nio.ByteBuffer.wrap(frame)));
        }, () -> { });
        try {
            var lane = writer.lane(2);
            // The first is taken by the writer and held in the sender; two more fill the lane.
            assertThat(lane.offer(TrunkFrame.text(1, "a"))).isTrue();
            assertThat(sentFirst.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(lane.offer(TrunkFrame.text(1, "b"))).isTrue();
            assertThat(lane.offer(TrunkFrame.text(1, "c"))).isTrue();

            assertThat(lane.offer(TrunkFrame.text(1, "d"))).isFalse();
            lane.offerControl(TrunkFrame.close(1, digital.demilich.henge.core.CloseStatus.NORMAL));
            release.countDown();

            await(() -> sent.size() == 4);
            assertThat(sent).extracting(TrunkFrame::type)
                    .containsExactly(TrunkFrame.TEXT, TrunkFrame.TEXT, TrunkFrame.TEXT, TrunkFrame.CLOSE);
        } finally {
            writer.stop();
        }
    }

    @Test
    void channelsTakeTurnsSoOneThatSendsALotDoesNotStarveTheOthers() throws Exception {
        var release = new CountDownLatch(1);
        var sentFirst = new CountDownLatch(1);
        List<Long> channels = Collections.synchronizedList(new ArrayList<>());
        var writer = new TrunkWriter(frame -> {
            sentFirst.countDown();
            release.await();
            channels.add(TrunkFrame.decode(java.nio.ByteBuffer.wrap(frame)).channel());
        }, () -> { });
        try {
            var busy = writer.lane(100);
            var quiet = writer.lane(100);
            busy.offer(TrunkFrame.text(1, "x"));
            assertThat(sentFirst.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) {
                busy.offer(TrunkFrame.text(1, "x"));
            }
            quiet.offer(TrunkFrame.text(2, "y"));
            release.countDown();

            await(() -> channels.size() == 22);
            // The quiet channel's frame goes out after at most one more of the busy one's, not after all 20.
            assertThat(channels.indexOf(2L)).isLessThanOrEqualTo(3);
        } finally {
            writer.stop();
        }
    }

    @Test
    void aFailedSendIsReportedOnce() throws Exception {
        var failures = new AtomicInteger();
        var writer = new TrunkWriter(frame -> {
            throw new java.io.IOException("connection reset");
        }, failures::incrementAndGet);
        try {
            var lane = writer.lane(10);
            lane.offer(TrunkFrame.text(1, "a"));
            lane.offer(TrunkFrame.text(1, "b"));

            await(() -> failures.get() == 1);
            Thread.sleep(100);
            assertThat(failures.get()).isEqualTo(1);
        } finally {
            writer.stop();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
