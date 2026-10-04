package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an internal Henge service boundary.
 *
 * <p>One or more implementations may be registered against this interface, each declaring which
 * version it provides via {@link ServiceVersion} in place of {@code @Service}. Each implementation
 * may run in-process ({@code embedded} mode) or in a separate process reached over HTTP
 * ({@code internal-rest}), decided entirely by deployment configuration
 * ({@code henge.services.<name>.*}, {@code henge.serve}). A dependency that doesn't pin a specific
 * version via {@link ServiceVersion} always resolves to {@link #defaultVersion()} — most services
 * only ever have one version and never need to think about this at all.
 *
 * <p>This annotation is only ever applied to internal service contracts. A project's
 * public-facing HTTP API is plain Spring MVC ({@code @RestController}, {@code @GetMapping},
 * ...) and never carries this annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HengeService {

    /**
     * Logical service name, used as the config key ({@code henge.services.<name>.*})
     * and in the dispatch path. Defaults to the interface's simple name in kebab case
     * ({@code AuditService} -> {@code audit-service}). Must be lowercase kebab case -- see
     * {@link ServiceNames}.
     */
    String name() default "";

    /**
     * The version used by a dependency that doesn't pin one explicitly via {@link ServiceVersion}.
     * Also carried in the dispatch path, so a breaking change can be rolled out as a new version
     * without an old client/server pairing silently misrouting or failing to deserialize.
     */
    int defaultVersion() default 1;
}
