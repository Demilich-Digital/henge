package digital.demilich.henge.examples.services;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.examples.contracts.AuditService;
import digital.demilich.henge.examples.contracts.AuditServiceSkeleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A second, independent implementation of {@link AuditService}, coexisting with
 * {@link AuditServiceImpl} (version 1) purely to demonstrate multi-version wiring — nothing
 * else in this example requires it, so it's only ever reached via an explicit
 * {@code @ServiceVersion(value = AuditService.class, version = 2)} qualifier. Unlike version
 * 1, this version actually supports {@code getRecentEvents} (it's {@code @AddedIn(2)}), so it
 * overrides the generated {@link AuditServiceSkeleton} stub for real instead of inheriting it.
 */
@ServiceVersion(value = AuditService.class, version = 2)
public class AuditServiceImplV2 extends AuditServiceSkeleton {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void recordEvent(String event) {
        events.add("v2:" + event);
    }

    @Override
    public ImmutableList<String> getEvents() {
        return ImmutableList.copyOf(events);
    }

    @Override
    public ImmutableList<String> getRecentEvents(int limit) {
        List<String> snapshot = new ArrayList<>(events);
        return ImmutableList.copyOf(snapshot.subList(Math.max(0, snapshot.size() - limit), snapshot.size()));
    }
}
