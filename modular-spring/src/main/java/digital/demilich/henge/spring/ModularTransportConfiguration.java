package digital.demilich.henge.spring;

import digital.demilich.henge.core.ServiceTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Plain-Spring core wiring: everything needed to <em>call</em> modular services (embedded or
 * remote) from this process, independent of whether it also <em>serves</em> any of them — see
 * {@link ModularDispatcherConfiguration} for that, or {@link ModularConfiguration} to import both
 * at once. Combined with {@link EnableModularServices} on your own {@code @Configuration} class,
 * this is the plain-Spring equivalent of {@code modular-spring-boot-starter}'s autoconfiguration.
 *
 * <p>Unlike the Boot starter, this deliberately does <em>not</em> provide a fallback empty
 * {@link ModularServiceRegistry} bean — always pair this with {@link EnableModularServices} on
 * your own {@code @Configuration} class, which registers the real one. Omitting it fails clearly
 * at startup ("no bean of type ModularServiceRegistry") rather than silently doing nothing.
 *
 * <p>The transport's {@code ObjectMapper}/{@code RestClient} are deliberately <em>not</em>
 * {@code @Bean}s (see {@link ModularTransportSupport}) — publishing an unqualified
 * {@code ObjectMapper} bean here could make Boot's {@code JacksonAutoConfiguration} back off from
 * the application's own {@code spring.jackson.*} configuration, and would risk ambiguity against a
 * user-defined {@code ObjectMapper}/{@code RestClient} bean.
 */
@Configuration
public class ModularTransportConfiguration {

    @Bean
    public ModularProperties modularProperties(Environment environment) {
        return new ModularProperties(environment);
    }

    @Bean
    public ServiceTransport internalRestTransport(ModularProperties modularProperties) {
        return new InternalRestTransport(
                ModularTransportSupport.restClient(modularProperties), ModularTransportSupport.objectMapper(), modularProperties);
    }
}
