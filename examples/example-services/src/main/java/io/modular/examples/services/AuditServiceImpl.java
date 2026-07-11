package io.modular.examples.services;

import io.modular.core.ServiceVersion;
import io.modular.examples.contracts.AuditService;
import io.modular.examples.contracts.AuditServiceSkeleton;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Extends the generated {@link AuditServiceSkeleton} rather than implementing {@link AuditService}
 * directly, so it doesn't have to implement {@code getRecentEvents} — that method is
 * {@code @AddedIn("2")}, and this is version "1". Calling it here falls through to the skeleton's
 * generated throwing stub.
 */
@ServiceVersion(value = AuditService.class, version = "1")
public class AuditServiceImpl extends AuditServiceSkeleton {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void recordEvent(String event) {
        events.add(event);
    }

    @Override
    public List<String> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
