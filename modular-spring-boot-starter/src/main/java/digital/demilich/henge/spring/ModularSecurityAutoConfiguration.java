package digital.demilich.henge.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Opt-in ({@code modular.server.permit-spring-security=true}): lets internal dispatch work in an
 * application that uses Spring Security, the way Spring Security intends separate concerns to
 * coexist -- a dedicated {@link SecurityFilterChain} matching only
 * {@code POST {modular.server.path-prefix}/**}, ordered ahead of the application's own chains.
 *
 * <p>Without it, Spring Security's default chain demands authentication and a CSRF token on every
 * POST, so every remote dispatch fails with 401 before it reaches {@link ModularDispatcherController}.
 * Whether {@code /_modular} should be opened at all, or protected by the application's own setup
 * (JWT, mTLS, ...), is the application's decision, so nothing changes unless asked. When asked, the
 * chain is explicit about what the endpoint is -- internal service-to-service RPC, not a
 * browser-facing one: requests are {@code permitAll} (the optional {@code modular.transport.secret},
 * checked by the dispatcher itself, is the only gate; otherwise the network is), sessions are
 * stateless, and CSRF protection is off, which is sound here because CSRF defends cookie/session
 * authentication this endpoint never has. Unlike {@code WebSecurity.ignoring()}, requests still pass
 * through the security filter chain machinery (headers, firewall, ...).
 *
 * <p>Nothing else the application serves is touched. This runs <em>after</em> Boot's
 * {@code SecurityAutoConfiguration} on purpose: Boot's default chain only registers when no
 * {@code SecurityFilterChain} bean exists yet, so registering ours afterwards leaves it in place --
 * the application keeps Boot's "authenticate everything" default -- instead of silently replacing it
 * and leaving the application's own endpoints unprotected. An application that wants a different
 * policy for {@code /_modular} defines its own bean named {@code modularSecurityFilterChain}, and
 * this one backs off.
 */
@AutoConfiguration(
        after = ModularAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HttpSecurity.class)
@ConditionalOnProperty(prefix = "modular.server", name = "permit-spring-security", havingValue = "true")
@ConditionalOnBean(value = {ModularDispatcherController.class, ModularProperties.class}, name = "springSecurityFilterChain")
public class ModularSecurityAutoConfiguration {

    static final String CHAIN_BEAN_NAME = "modularSecurityFilterChain";

    @Bean(CHAIN_BEAN_NAME)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = CHAIN_BEAN_NAME)
    SecurityFilterChain modularSecurityFilterChain(HttpSecurity http, ModularProperties modularProperties) throws Exception {
        String pattern = modularProperties.getServerPathPrefix() + "/**";
        return http
                .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
