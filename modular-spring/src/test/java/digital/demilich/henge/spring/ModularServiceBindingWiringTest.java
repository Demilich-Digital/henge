package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.echo.EchoService;
import digital.demilich.henge.spring.fixture.echo.EchoServiceImpl;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

/** The binding as Spring wires it: what callers are handed, and where the interceptors come from. */
class ModularServiceBindingWiringTest {

    /** Takes every {@code EchoService} the way a consumer would, and records what the interceptors saw. */
    static class Collected {
        final List<EchoService> services;
        final List<String> events = new CopyOnWriteArrayList<>();

        Collected(List<EchoService> services) {
            this.services = services;
        }
    }

    @Configuration
    @Import(EchoTestConfig.class)
    static class Config {

        @Bean
        Collected collected(List<EchoService> services) {
            return new Collected(services);
        }

        // Declared in the opposite order to their @Order, so the order can't be the declaration order.
        @Bean
        @Order(2)
        ServiceCallInterceptor inner(List<String> events) {
            return (invocation, chain) -> {
                events.add("inner " + chain.mode());
                return chain.proceed();
            };
        }

        @Bean
        @Order(1)
        ServiceCallInterceptor outer(List<String> events) {
            return (invocation, chain) -> {
                events.add("outer " + invocation.serviceName() + "@" + invocation.serviceVersion() + "#" + invocation.methodName());
                return chain.proceed();
            };
        }

        @Bean
        List<String> events() {
            return new CopyOnWriteArrayList<>();
        }
    }

    @Test
    void aConsumerInjectingEveryServiceOfATypeGetsTheProxyAndNotTheHiddenImplementation() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            List<EchoService> injected = ctx.getBean(Collected.class).services;

            assertThat(injected).hasSize(1);
            assertThat(Proxy.isProxyClass(injected.get(0).getClass())).isTrue();
        }
    }

    @Configuration
    @Import(EchoTestConfig.class)
    static class ConcreteInjectionConfig {

        @Bean
        Object wantsTheImplementation(EchoServiceImpl implementation) {
            return implementation;
        }
    }

    @Test
    void anImplementationCannotBeInjectedByItsConcreteClass() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(ConcreteInjectionConfig.class).close())
                .hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class)
                .hasStackTraceContaining(EchoServiceImpl.class.getName());
    }

    @Test
    void interceptorBeansWrapEveryCallInOrder() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            ctx.getBean(EchoService.class).echo("x");

            @SuppressWarnings("unchecked")
            List<String> events = ctx.getBean("events", List.class);
            assertThat(events).containsExactly("outer echo-service@1#echo", "inner EMBEDDED");
        }
    }

    @Test
    void theDispatcherReachesTheImplementationWithoutTheInterceptors() throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var registry = ctx.getBean(ModularServiceRegistry.class);
            var descriptor = registry.find("echo-service", 1).orElseThrow();

            Object result = registry.binding(descriptor).invokeLocal(EchoService.class.getMethod("echo", String.class), new Object[] {"x"});

            assertThat(result).isEqualTo("echo:x");
            assertThat(ctx.getBean(EchoServiceImpl.class).getCallCount()).isEqualTo(1);
            assertThat(ctx.getBean("events", List.class)).isEmpty();
        }
    }
}
