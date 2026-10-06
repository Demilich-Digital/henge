package digital.demilich.henge.spring;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Boot-specific classpath auto-detection layer on top of the plain-Spring
 * {@link HengeTransportConfiguration}: unconditionally imports the transport wiring (the
 * {@code ServiceTransport} and {@link HengeProperties} beans -- its RestClient is deliberately not
 * a bean), then adds Boot-only conveniences that don't have a plain-Spring equivalent — an empty
 * {@link HengeServiceRegistry} fallback when {@link EnableHengeServices} wasn't used, and
 * gating the dispatcher controller behind a servlet web application and {@code henge.server.enabled}. See {@code henge-spring}'s {@link HengeConfiguration} for
 * the plain-Spring path, where "should this process serve requests" is a code-level `@Import`
 * choice instead of a property.
 *
 * <p>The dispatcher's {@code ObjectMapper} is built via {@link HengeTransportSupport} rather than
 * injected as an unqualified bean, for the same reason as {@code henge-spring}'s
 * {@link HengeDispatcherConfiguration} — see its Javadoc.
 */
@AutoConfiguration
@Import(HengeTransportConfiguration.class)
public class HengeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HengeServiceRegistry hengeServiceRegistry() {
        return new HengeServiceRegistry(List.of());
    }

    @Bean
    @ConditionalOnMissingBean
    HengeTopologyCatalog hengeTopologyCatalog() {
        return new HengeTopologyCatalog(List.of());
    }

    /**
     * The topology endpoint and page, when {@code henge.topology.enabled=true} (the imported
     * configuration checks that), in a servlet web application that serves {@code /_henge} at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "henge.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    @Import(HengeTopologyConfiguration.class)
    static class Topology {
    }

    /** A request that failed for want of the ephemeral store is a {@code 503} at this process's edge, unless the application says otherwise. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    HengeStoreUnavailableAdvice hengeStoreUnavailableAdvice() {
        return new HengeStoreUnavailableAdvice();
    }

    /**
     * Only in a servlet web application -- a process that serves no HTTP has nothing to dispatch to it.
     * Backs off when the application already imported {@link HengeDispatcherConfiguration} itself.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "henge.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    HengeDispatcherController hengeDispatcherController(
            HengeServiceRegistry registry, HengeProperties hengeProperties, ObjectProvider<ServiceDispatchObserver> observer) {
        return new HengeDispatcherController(registry, HengeTransportSupport.objectMapper(), hengeProperties,
                observer.getIfAvailable(() -> ServiceDispatchObserver.NONE));
    }
}
