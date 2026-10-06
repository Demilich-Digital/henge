package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MailboxTest {

    @Test
    void tasksRunInTheOrderTheyWereOffered() throws Exception {
        var mailbox = new Mailbox(1000);
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        var done = new CountDownLatch(500);

        for (int i = 0; i < 500; i++) {
            int n = i;
            mailbox.offer(() -> {
                ran.add(n);
                done.countDown();
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ran).isSortedAccordingTo(Integer::compare).hasSize(500);
    }

    @Test
    void aSlowTaskHoldsUpOnlyItsOwnMailbox() throws Exception {
        var slow = new Mailbox(10);
        var other = new Mailbox(10);
        var release = new CountDownLatch(1);
        var otherRan = new CountDownLatch(1);
        slow.offer(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        other.offer(otherRan::countDown);

        assertThat(otherRan.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void aFullMailboxRefusesTheNextTask() throws Exception {
        var mailbox = new Mailbox(2);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        // The first is taken off the queue to run, and blocks; two more fill it.
        mailbox.offer(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(mailbox.offer(() -> { })).isTrue();
        assertThat(mailbox.offer(() -> { })).isTrue();
        assertThat(mailbox.offer(() -> { })).isFalse();
        release.countDown();
    }

    @Test
    void aTaskThatThrowsDoesNotStopTheOnesAfterIt() throws Exception {
        var mailbox = new Mailbox(10);
        var ran = new CountDownLatch(1);

        mailbox.offer(() -> {
            throw new IllegalStateException("boom");
        });
        mailbox.offer(ran::countDown);

        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
