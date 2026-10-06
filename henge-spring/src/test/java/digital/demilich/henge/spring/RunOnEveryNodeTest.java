package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.everynodefixture.plain.GatewayService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class RunOnEveryNodeTest {

    @Configuration
    @EnableHengeServices(basePackages = {"digital.demilich.henge.spring.everynodefixture.plain",
            "digital.demilich.henge.spring.everynodefixture.other"})
    static class Both {
    }

    @Configuration
    @EnableHengeServices(basePackages = "digital.demilich.henge.spring.everynodefixture.leased")
    static class Leased {
    }

    private static AnnotationConfigApplicationContext context(Class<?> config, Map<String, Object> properties) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(config);
        return ctx;
    }

    @Test
    void isHostedWhenNothingNarrowsIt() {
        try (var ctx = context(Both.class, Map.of())) {
            ctx.refresh();
            assertThat(ctx.getBean(GatewayService.class).route("/a")).isEqualTo("routed /a");
        }
    }

    @Test
    void isHostedWhenServeListsIt() {
        try (var ctx = context(Both.class, Map.of("henge.serve", "gateway-service", "henge.store.type", "in-process"))) {
            ctx.refresh();
            assertThat(ctx.getBean(GatewayService.class).route("/a")).isEqualTo("routed /a");
        }
    }

    @Test
    void aServeThatLeavesItOutFailsStartup() {
        try (var ctx = context(Both.class, Map.of("henge.serve", "audit-service"))) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("GatewayService is @RunOnEveryNode")
                    .hasStackTraceContaining("gateway-service@1")
                    .hasStackTraceContaining("henge.serve");
        }
    }

    @Test
    void anExplicitInternalRestModeFailsStartup() {
        try (var ctx = context(Both.class, Map.of("henge.services.gateway-service.mode", "internal-rest",
                "henge.services.gateway-service.url", "http://elsewhere"))) {
            assertThatThrownBy(ctx::refresh).hasStackTraceContaining("GatewayService is @RunOnEveryNode");
        }
    }

    @Test
    void anImplementationThatWaitsOnALeaseFailsStartup() {
        try (var ctx = context(Leased.class, Map.of("henge.leases.gateway-db.capacity", 4,
                "henge.leases.gateway-db.amount", 2))) {
            assertThatThrownBy(ctx::refresh)
                    .hasStackTraceContaining("PooledGatewayService is @RunOnEveryNode")
                    .hasStackTraceContaining("@RequiresLease");
        }
    }
}
