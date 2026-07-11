package io.modular.examples.contracts;

import io.modular.core.ModularService;
import java.util.List;

@ModularService(name = "audit-service")
public interface AuditService {

    void recordEvent(String event);

    List<String> getEvents();
}
