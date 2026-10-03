package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.spring.fixture.counter.CounterService;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV1;
import digital.demilich.henge.spring.fixture.counter.CounterServiceV2;
import digital.demilich.henge.spring.fixture.counter.CounterTestConfig;
import digital.demilich.henge.spring.fixture.counter.DefaultConsumer;
import digital.demilich.henge.spring.fixture.counter.PinnedConsumer;
import digital.demilich.henge.spring.fixture.echo.EchoTestConfig;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * Proves the core wiring mechanism — default-version resolution via {@code @Primary}, explicit
 * version pinning via {@code @ServiceVersion} qualifiers, and {@code --modular.serve}-driven mode
 * selection — works with a plain {@link AnnotationConfigApplicationContext}, no Spring Boot
 * anywhere. Mirrors {@code modular-spring-boot-starter}'s {@code ModularServiceMultiVersionTest}
 * scenarios, minus the Boot bootstrap.
 */
class ModularServiceRegistrarPlainSpringTest {

    @Test
    void unqualifiedDependencyResolvesToDefaultVersion() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            DefaultConsumer consumer = ctx.getBean(DefaultConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v1");
        }
    }

    @Test
    void qualifiedDependencyResolvesToPinnedVersion() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            PinnedConsumer consumer = ctx.getBean(PinnedConsumer.class);
            assertThat(consumer.describe()).isEqualTo("v2");
        }
    }

    @Test
    void bothVersionsAreRegisteredAsDistinctBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CounterTestConfig.class)) {
            assertThat(ctx.getBeansOfType(CounterService.class)).hasSize(2);
            assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
            assertThat(ctx.getBean(CounterServiceV2.class)).isNotNull();
        }
    }

    @Test
    void modularServePropertyOverridesLocalImplDefaultToRemote() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.serve", "counter-service@1")));
        ctx.register(CounterTestConfig.class, ModularTransportConfiguration.class);
        ctx.refresh();
        try {
            // v1 is served here -> real bean, unaffected.
            assertThat(ctx.getBean(CounterServiceV1.class)).isNotNull();
            // v2 has a local impl too, but isn't listed in modular.serve -> proxy instead.
            assertThat(ctx.getBeanProvider(CounterServiceV2.class).getIfAvailable()).isNull();

            boolean anyProxy = ctx.getBeansOfType(CounterService.class).values().stream()
                    .anyMatch(bean -> Proxy.isProxyClass(bean.getClass()));
            assertThat(anyProxy).isTrue();
        } finally {
            ctx.close();
        }
    }

    @Test
    void modularServeNamingAnUnknownServiceFailsFast() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.serve", "counter-servce")));
        ctx.register(CounterTestConfig.class, ModularTransportConfiguration.class);

        assertThatThrownBy(ctx::refresh)
                .hasStackTraceContaining("modular.serve names [counter-servce]")
                .hasStackTraceContaining("counter-service");
        ctx.close();
    }

    @Test
    void anInvalidServiceNameFailsAtStartupEvenWithoutTheProcessor() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(BadNameConfig.class).close())
                .hasStackTraceContaining("'billing/v2'")
                .hasStackTraceContaining("lowercase kebab case");
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.badname")
    static class BadNameConfig {
    }

    @Test
    void aStereotypedImplementationFailsAtStartupEvenWithoutTheProcessor() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(StereotypedConfig.class).close())
                .hasStackTraceContaining("StereotypedServiceImpl is annotated both @ServiceVersion and a Spring stereotype");
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.stereotyped")
    static class StereotypedConfig {
    }

    @Test
    void twoImplementationsClaimingTheSameVersionFailAtStartup() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(DuplicateVersionConfig.class).close())
                .hasStackTraceContaining("Two implementations both claim version '1'")
                .hasStackTraceContaining("DuplicateServiceImplA")
                .hasStackTraceContaining("DuplicateServiceImplB");
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.duplicateversion")
    static class DuplicateVersionConfig {
    }

    @Test
    void aSecondEnableModularServicesFailsFastNamingBoth() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(CounterTestConfig.class, EchoTestConfig.class).close())
                .hasStackTraceContaining("@EnableModularServices is declared on both")
                .hasStackTraceContaining(CounterTestConfig.class.getName())
                .hasStackTraceContaining(EchoTestConfig.class.getName());
    }

    @Test
    void anImplementationWhoseInterfaceIsNotScannedNamesThePackageToAdd() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(ImplOnlyConfig.class).close())
                .hasStackTraceContaining("that interface's package isn't scanned")
                .hasStackTraceContaining("\"digital.demilich.henge.spring.outofscope.api\"");
    }

    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.outofscope.impl")
    static class ImplOnlyConfig {
    }

    @Test
    void anInternalRestServiceWithoutTheTransportConfigurationSaysWhatToImport() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("modular.services.counter-service.mode", "internal-rest")));
        ctx.register(CounterTestConfig.class); // no ModularTransportConfiguration

        assertThatThrownBy(ctx::refresh)
                .hasStackTraceContaining("no ServiceTransport bean")
                .hasStackTraceContaining("@Import ModularTransportConfiguration");
        ctx.close();
    }

    @Test
    void overlappingBasePackagesDoNotReportADuplicateImplementation() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(OverlappingPackagesConfig.class)) {
            assertThat(ctx.getBeansOfType(CounterService.class)).hasSize(2);
        }
    }

    @Configuration
    @EnableModularServices(basePackages = {
        "digital.demilich.henge.spring.fixture.counter", "digital.demilich.henge.spring.fixture"
    })
    static class OverlappingPackagesConfig {
    }

    /**
     * Class files are discovered through the bean factory's classloader, not whatever the
     * thread-context loader happens to be, so scanning and class loading can't disagree.
     */
    @Test
    void scanningReadsClassFilesThroughTheContextClassLoader() {
        AtomicInteger resourceLookups = new AtomicInteger();
        ClassLoader counting = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                resourceLookups.incrementAndGet();
                return super.getResources(name);
            }
        };
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.setClassLoader(counting);
        ctx.register(ScanOnlyConfig.class);
        ctx.refresh();
        try {
            assertThat(ctx.getBeansOfType(CounterService.class)).hasSize(2);
            assertThat(resourceLookups.get()).isPositive();
        } finally {
            ctx.close();
        }
    }

    /** No {@code @ComponentScan}: the only classpath scanning here is the registrar's own. */
    @Configuration
    @EnableModularServices(basePackages = "digital.demilich.henge.spring.fixture.counter")
    static class ScanOnlyConfig {
    }
}
