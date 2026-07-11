package io.modular.examples.services;

import io.modular.core.ServiceVersion;
import io.modular.examples.contracts.AuditService;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ServiceVersion(value = AuditService.class, version = "1")
public class AuditServiceImpl implements AuditService {

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
