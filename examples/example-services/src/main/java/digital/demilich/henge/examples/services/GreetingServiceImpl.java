package digital.demilich.henge.examples.services;

import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.examples.contracts.AuditService;
import digital.demilich.henge.examples.contracts.GreetingService;

/**
 * {@code @ServiceVersion} stands in for {@code @Service} on modular service implementations —
 * the framework registers this bean itself, so it must not also carry {@code @Service}. Note it
 * pins its {@link AuditService} dependency to version "1" explicitly; whether that dependency
 * turns out to be the real local implementation or an HTTP-backed proxy is decided entirely by
 * config, not by anything visible in this class.
 */
@ServiceVersion(value = GreetingService.class, version = 1)
public class GreetingServiceImpl implements GreetingService {

    private final AuditService auditService;

    public GreetingServiceImpl(@ServiceVersion(value = AuditService.class, version = 1) AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public String greet(String name) {
        auditService.recordEvent("greeted:" + name);
        return "Hello, " + name + "!";
    }
}
