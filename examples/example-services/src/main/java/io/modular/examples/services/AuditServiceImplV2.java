package io.modular.examples.services;

import io.modular.core.ServiceVersion;
import io.modular.examples.contracts.AuditService;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A second, independent implementation of {@link AuditService}, coexisting with
 * {@link AuditServiceImpl} (version "1") purely to demonstrate multi-version wiring — nothing
 * else in this example requires it, so it's only ever reached via an explicit
 * {@code @ServiceVersion(value = AuditService.class, version = "2")} qualifier.
 */
@ServiceVersion(value = AuditService.class, version = "2")
public class AuditServiceImplV2 implements AuditService {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void recordEvent(String event) {
        events.add("v2:" + event);
    }

    @Override
    public List<String> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
