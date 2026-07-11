package io.modular.examples.app;

import io.modular.core.ServiceVersion;
import io.modular.examples.contracts.AuditService;
import io.modular.examples.contracts.GreetingService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project's public-facing HTTP API: plain Spring MVC, fully developer-owned, and completely
 * unaware of whether {@link GreetingService} or {@link AuditService} are running embedded in
 * this same process or being dispatched to another one over {@code /_modular/**} — that's the
 * whole point.
 *
 * <p>{@code auditServiceV2} demonstrates pinning a dependency to a specific, non-default version
 * via {@code @ServiceVersion} — {@code auditService} (no qualifier) always resolves to
 * {@code AuditService}'s default version ("1"), regardless of what else is deployed alongside it.
 */
@RestController
@RequestMapping("/api")
class DemoController {

    private final GreetingService greetingService;
    private final AuditService auditService;
    private final AuditService auditServiceV2;

    DemoController(
            GreetingService greetingService,
            AuditService auditService,
            @ServiceVersion(value = AuditService.class, version = "2") AuditService auditServiceV2) {
        this.greetingService = greetingService;
        this.auditService = auditService;
        this.auditServiceV2 = auditServiceV2;
    }

    @GetMapping("/greet/{name}")
    String greet(@PathVariable("name") String name) {
        return greetingService.greet(name);
    }

    @GetMapping("/audit")
    List<String> audit() {
        return auditService.getEvents();
    }

    @GetMapping("/audit/v2/{event}")
    List<String> recordAndListV2(@PathVariable("event") String event) {
        auditServiceV2.recordEvent(event);
        return auditServiceV2.getEvents();
    }
}
