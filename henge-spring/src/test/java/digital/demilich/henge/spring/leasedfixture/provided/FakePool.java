package digital.demilich.henge.spring.leasedfixture.provided;

import java.util.concurrent.atomic.AtomicInteger;

/** A final resource class, as a real connection pool often is. */
public final class FakePool implements AutoCloseable {

    public static final AtomicInteger OPENED = new AtomicInteger();
    public static final AtomicInteger CLOSED = new AtomicInteger();

    private final int size;

    FakePool(int size) {
        this.size = size;
        OPENED.incrementAndGet();
    }

    public int size() {
        return size;
    }

    @Override
    public void close() {
        CLOSED.incrementAndGet();
    }
}
