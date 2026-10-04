package digital.demilich.henge.spring;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletPath;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Makes internal dispatch work in an application that uses Spring Security, and makes the
 * {@code modular.transport.secret} a real Spring Security authentication rather than a side channel.
 *
 * <p>Without this, Spring Security's default chain demands a user session and a CSRF token on every
 * POST, so every remote dispatch fails with 401 before it reaches {@link ModularDispatcherController}.
 * With Spring Security present this adds a dedicated {@link SecurityFilterChain} matching only
 * {@code POST {modular.server.path-prefix}/**}, ordered ahead of the application's own chains. With
 * {@code modular.topology.enabled} it also covers {@code GET {prefix}/topology} and
 * {@code GET {prefix}/topology/ui}: the JSON is held to the same secret as dispatch, the page
 * (which holds no data, and asks for the secret itself) is always open:
 *
 * <ul>
 *   <li><b>A secret is configured</b> -- the {@code Modular-Internal-Secret} header is verified (by
 *       the same {@link SharedSecret} the dispatcher uses) and, if valid, becomes an authenticated
 *       principal ({@link #PRINCIPAL}, holding {@code ROLE_}{@link #ROLE}). The chain requires an
 *       authenticated caller, so a missing or wrong secret is rejected by Spring Security with
 *       {@code 403}, the same status the dispatcher answers without Spring Security. Application
 *       code, method security and auditing can see who the caller is.</li>
 *   <li><b>No secret</b> -- requests are {@code permitAll}, stated explicitly: the network is the
 *       boundary, a supported configuration.</li>
 * </ul>
 *
 * Either way sessions are stateless and CSRF protection is off, which is sound here because CSRF
 * defends cookie/session authentication, and this endpoint has none.
 *
 * <p>Nothing else the application serves is touched. This runs <em>after</em> Boot's
 * {@code SecurityAutoConfiguration} on purpose: Boot's default chain only registers when no
 * {@code SecurityFilterChain} bean exists yet, so registering ours afterwards leaves it in place --
 * the application keeps Boot's "authenticate everything" default -- instead of silently replacing it
 * and leaving the application's own endpoints unprotected. An application that wants a different
 * policy for {@code /_modular} (JWT, mTLS, ...) defines its own bean named
 * {@code modularSecurityFilterChain}, and this one backs off.
 */
@AutoConfiguration(
        after = ModularAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(HttpSecurity.class)
@ConditionalOnBean(value = {ModularDispatcherController.class, ModularProperties.class}, name = "springSecurityFilterChain")
public class ModularSecurityAutoConfiguration {

    static final String CHAIN_BEAN_NAME = "modularSecurityFilterChain";

    /** The principal an authenticated internal caller carries. */
    public static final String PRINCIPAL = "modular-internal-service";

    /** The role granted to an authenticated internal caller, usable as {@code hasRole("MODULAR_SERVICE")}. */
    public static final String ROLE = "MODULAR_SERVICE";

    @Bean(CHAIN_BEAN_NAME)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = CHAIN_BEAN_NAME)
    SecurityFilterChain modularSecurityFilterChain(HttpSecurity http, ModularProperties modularProperties,
            ObjectProvider<DispatcherServletPath> dispatcherServletPath) throws Exception {
        SharedSecret secret = SharedSecret.from(modularProperties);
        AuthenticationEntryPoint rejectWith403 = new HttpStatusEntryPoint(HttpStatus.FORBIDDEN);

        // The dispatcher's mapping is relative to the DispatcherServlet; under spring.mvc.servlet.path=/api
        // the request path is /api/_modular/..., which a bare /_modular/** matcher never sees -- the call
        // then falls through to the application's own chain and gets a 401.
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        DispatcherServletPath servletPath = dispatcherServletPath.getIfAvailable();
        if (servletPath != null && !servletPath.getPrefix().isEmpty()) {
            paths = paths.basePath(servletPath.getPrefix());
        }

        String prefix = modularProperties.getServerPathPrefix();
        RequestMatcher dispatch = paths.matcher(HttpMethod.POST, prefix + "/**");
        RequestMatcher topologyPage = paths.matcher(HttpMethod.GET, prefix + "/topology/ui");
        RequestMatcher covered = modularProperties.isTopologyEnabled()
                ? new OrRequestMatcher(dispatch, paths.matcher(HttpMethod.GET, prefix + "/topology"), topologyPage)
                : dispatch;

        http.securityMatcher(covered)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(rejectWith403));

        if (secret.isRequired()) {
            http.addFilterBefore(secretAuthenticationFilter(secret, rejectWith403), AuthorizationFilter.class)
                    .authorizeHttpRequests(requests -> requests.requestMatchers(topologyPage).permitAll().anyRequest().authenticated());
        } else {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        }
        return http.build();
    }

    /**
     * Header present: verify it, and a wrong one fails right here. Header absent: pass through
     * unauthenticated, and {@code authenticated()} turns that into the entry point's rejection.
     */
    private static AuthenticationFilter secretAuthenticationFilter(SharedSecret secret, AuthenticationEntryPoint entryPoint) {
        AuthenticationManager manager = authentication -> {
            if (!secret.accepts((String) authentication.getCredentials())) {
                throw new BadCredentialsException("Invalid " + ModularDispatcherController.SECRET_HEADER + " header");
            }
            return UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        };
        AuthenticationFilter filter = new AuthenticationFilter(manager, request -> {
            String provided = request.getHeader(ModularDispatcherController.SECRET_HEADER);
            return provided == null ? null : UsernamePasswordAuthenticationToken.unauthenticated(PRINCIPAL, provided);
        });
        filter.setFailureHandler(new AuthenticationEntryPointFailureHandler(entryPoint));
        // The default success handler redirects to a "saved request"; a stateless API call should just
        // carry on down the chain (the no-op 3-argument form still continues via the default 4-argument one).
        filter.setSuccessHandler((request, response, authentication) -> { });
        return filter;
    }
}
