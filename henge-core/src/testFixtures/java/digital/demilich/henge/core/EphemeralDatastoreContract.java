package digital.demilich.henge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@link SystemEphemeralDatastore} contract, as tests every store has to pass: a store's own test
 * extends this and says how to reach the store and how to let time pass. The tests use one node, and
 * keys no other test uses, so they hold for a store whose nodes share nothing and for one that is
 * shared with other tests.
 */
public abstract class EphemeralDatastoreContract {

    /** Long enough that nothing lapses during a test. */
    protected static final Duration LONG = Duration.ofSeconds(30);
    /** Short enough that {@link #advance} past it is quick on a store that runs on real time. */
    protected static final Duration SHORT = Duration.ofMillis(200);

    /** The store under test. */
    protected abstract SystemEphemeralDatastore store();

    /** Lets at least {@code duration} pass on the store's clock. */
    protected abstract void advance(Duration duration) throws InterruptedException;

    /** A key no other test uses. */
    protected String newKey() {
        return "contract:" + UUID.randomUUID();
    }

    private MemberId member(String localName) {
        return new MemberId(store().nodeId(), localName);
    }

    // count

    @Test
    void countIsTheLiveMembersWhenNoneHaveLapsed() {
        String key = newKey();
        store().put(key, "a", new byte[] {1}, LONG);
        store().put(key, "removed", new byte[] {2}, LONG);
        store().put(key, "b", new byte[] {3}, LONG);
        store().remove(key, "removed");

        var count = store().count(key);
        var snapshot = store().read(key);

        assertThat(count.live()).isEqualTo(2).isEqualTo(snapshot.members().size());
        assertThat(count.epoch()).isEqualTo(snapshot.epoch());
    }

    @Test
    void countIsNeverFewerThanTheLiveMembersAndMayIncludeLapsedOnes() throws InterruptedException {
        String key = newKey();
        store().put(key, "staying", new byte[] {1}, LONG);
        store().put(key, "lapsing", new byte[] {2}, SHORT);
        advance(SHORT.multipliedBy(2));

        // Counted before anything reads the key, which may reclaim what has lapsed.
        var count = store().count(key);

        assertThat(count.live()).isBetween(1, 2);
        assertThat(store().read(key).members()).hasSize(1);
    }

    @Test
    void countOfAnUnknownKeyIsZero() {
        assertThat(store().count(newKey()).live()).isZero();
    }

    @Test
    void countSeesRenewedMembersAndClaims() throws InterruptedException {
        String key = newKey();
        store().claim(key, "a", 1, 10, SHORT);
        store().claim(key, "b", 1, 10, LONG);
        store().claim(key, "a", 1, 10, LONG);
        advance(SHORT.multipliedBy(2));

        assertThat(store().count(key).live()).isEqualTo(2);
    }

    // sample

    @Test
    void aSampleHasAtMostTheLimitAndCountsEveryMember() {
        String key = newKey();
        for (int i = 0; i < 10; i++) {
            store().put(key, "m" + i, new byte[] {(byte) i}, LONG);
        }

        var sample = store().sample(key, 3);
        var snapshot = store().read(key);

        assertThat(sample.members()).hasSize(3);
        assertThat(sample.live()).isEqualTo(10);
        assertThat(snapshot.members().keySet()).containsAll(sample.members().keySet());
        sample.members().forEach((id, value) -> assertThat(value).isEqualTo(snapshot.members().get(id)));
        assertThat(sample.epoch()).isEqualTo(snapshot.epoch());
    }

    @Test
    void aSampleLargerThanTheKeyIsTheWholeKey() {
        String key = newKey();
        for (int i = 0; i < 4; i++) {
            store().put(key, "m" + i, new byte[] {(byte) i}, LONG);
        }

        var sample = store().sample(key, 20);

        assertThat(sample.members().keySet()).isEqualTo(store().read(key).members().keySet());
        assertThat(sample.live()).isEqualTo(4);
    }

    @Test
    void aSampleNeverHasALapsedMember() throws InterruptedException {
        String key = newKey();
        for (int i = 0; i < 5; i++) {
            store().put(key, "staying" + i, new byte[] {1}, LONG);
            store().put(key, "lapsing" + i, new byte[] {1}, SHORT);
        }
        advance(SHORT.multipliedBy(2));

        for (int i = 0; i < 20; i++) {
            var sample = store().sample(key, 5);
            assertThat(sample.members().keySet()).allSatisfy(id -> assertThat(id.localName()).startsWith("staying"));
            assertThat(sample.members()).hasSize(5);
            assertThat(sample.live()).isBetween(5, 10);
        }
    }

    @Test
    void aSampleOfAnUnknownKeyIsEmpty() {
        var sample = store().sample(newKey(), 5);

        assertThat(sample.members()).isEmpty();
        assertThat(sample.live()).isZero();
    }

    @Test
    void overManyDrawsEveryMemberIsSampled() {
        String key = newKey();
        Set<MemberId> all = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            store().put(key, "m" + i, new byte[] {1}, LONG);
            all.add(member("m" + i));
        }

        Set<MemberId> seen = new HashSet<>();
        for (int i = 0; i < 200 && !seen.equals(all); i++) {
            seen.addAll(store().sample(key, 2).members().keySet());
        }

        assertThat(seen).isEqualTo(all);
    }

    @Test
    void aSampleOfLessThanOneIsTheCallersMistake() {
        assertThatThrownBy(() -> store().sample(newKey(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // tryAcquire

    @Test
    void aGrantHasNoWait() {
        var acquisition = store().tryAcquire(newKey(), 1, RateLimit.perSecond(1, 5));

        assertThat(acquisition.granted()).isTrue();
        assertThat(acquisition.retryAfter()).isZero();
    }

    @Test
    void aRefusalWaitsUntilOnePermitFits() {
        String key = newKey();
        var limit = new RateLimit(2, 1, Duration.ofSeconds(10));
        store().tryAcquire(key, 2, limit);

        var acquisition = store().tryAcquire(key, 1, limit);

        assertThat(acquisition.granted()).isFalse();
        // A full bucket frees one permit a period after it filled, less what has leaked since.
        assertThat(acquisition.retryAfter()).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(10));
    }

    @Test
    void aRefusalOfSeveralWhenOneFitsHasNoWait() {
        String key = newKey();
        var limit = new RateLimit(5, 1, Duration.ofSeconds(10));
        store().tryAcquire(key, 3, limit);

        var acquisition = store().tryAcquire(key, 3, limit);

        assertThat(acquisition.granted()).isFalse();
        assertThat(acquisition.retryAfter()).isZero();
    }

    @Test
    void afterTheWaitOnePermitFits() throws InterruptedException {
        String key = newKey();
        var limit = new RateLimit(1, 1, Duration.ofMillis(300));
        store().tryAcquire(key, 1, limit);
        var refused = store().tryAcquire(key, 1, limit);
        assertThat(refused.granted()).isFalse();
        assertThat(refused.retryAfter()).isPositive();

        advance(refused.retryAfter());

        assertThat(store().tryAcquire(key, 1, limit).granted()).isTrue();
    }

    @Test
    void aRequestLargerThanTheCapacityIsRefusedWithTheWaitForOnePermit() {
        var acquisition = store().tryAcquire(newKey(), 6, RateLimit.perSecond(1, 5));

        assertThat(acquisition.granted()).isFalse();
        assertThat(acquisition.retryAfter()).isZero();
    }
}
