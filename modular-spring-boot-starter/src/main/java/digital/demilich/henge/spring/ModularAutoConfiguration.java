package digital.demilich.henge.spring;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Boot-specific classpath auto-detection layer on top of the plain-Spring
 * {@link ModularTransportConfiguration}: unconditionally imports the transport wiring (RestClient,
 * {@code ServiceTransport}, {@link ModularProperties}), then adds Boot-only conveniences that
 * don't have a plain-Spring equivalent — an empty {@link ModularServiceRegistry} fallback when
 * {@link EnableModularServices} wasn't used, and gating the dispatcher controller behind
 * {@code modular.server.enabled}. See {@code modular-spring}'s {@link ModularConfiguration} for
 * the plain-Spring path, where "should this process serve requests" is a code-level `@Import`
 * choice instead of a property.
 *
 * <p>The dispatcher's {@code ObjectMapper} is built via {@link ModularTransportSupport} rather than
 * injected as an unqualified bean, for the same reason as {@code modular-spring}'s
 * {@link ModularDispatcherConfiguration} — see its Javadoc.
 */
@AutoConfiguration
@Import(ModularTransportConfiguration.class)
public class ModularAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModularServiceRegistry modularServiceRegistry() {
        return new ModularServiceRegistry(List.of());
    }

    @Bean
    @ConditionalOnProperty(prefix = "modular.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModularDispatcherController modularDispatcherController(
            ApplicationContext applicationContext, ModularServiceRegistry registry, ModularProperties modularProperties) {
        return new ModularDispatcherController(applicationContext, registry, ModularTransportSupport.objectMapper(), modularProperties);
    }

    /**
     * Opt-in: {@code modular.server.permit-spring-security=true}. With Spring Security on the
     * classpath its default filter chain demands authentication and a CSRF token on every POST,
     * which makes every remote dispatch fail with 401 before it reaches
     * {@link ModularDispatcherController}. Whether {@code /_modular/**} should be opened to it, or
     * protected by the application's own Spring Security setup (JWT, mTLS, ...), is the
     * application's call, so nothing is changed unless asked. When asked, Spring Security is told
     * to skip exactly {@code POST {path-prefix}/**} -- a {@link WebSecurityCustomizer} rather than a
     * {@code SecurityFilterChain} bean, because defining any chain makes Boot's default one back off
     * and would silently unprotect the application's own endpoints. Nothing else is affected.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebSecurityCustomizer.class)
    @ConditionalOnProperty(prefix = "modular.server", name = "permit-spring-security", havingValue = "true")
    static class SpringSecurityConfiguration {

        @Bean
        WebSecurityCustomizer modularWebSecurityCustomizer(ModularProperties modularProperties) {
            String pattern = modularProperties.getServerPathPrefix() + "/**";
            return web -> web.ignoring().requestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern));
        }
    }
}
