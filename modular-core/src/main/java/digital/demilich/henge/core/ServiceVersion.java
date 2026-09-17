package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Declares a specific version of a {@link ModularService} interface — used in two positions:
 *
 * <ul>
 *   <li>On an implementation class, in place of {@code @Service}: declares that this class
 *       provides {@link #version()} of {@link #value()}. The framework registers the bean
 *       itself; do not also annotate the class with {@code @Service}/{@code @Component} or it
 *       will be registered twice.</li>
 *   <li>On a constructor parameter or field of another modular service implementation: pins that
 *       dependency to a specific version, instead of the interface's default version. Omit this
 *       annotation entirely to get the default version ({@code @ModularService.defaultVersion()})
 *       — most services only ever have one version and never need to use this at an injection
 *       site at all.</li>
 * </ul>
 *
 * <p>Both attributes are mandatory in both positions (no defaults). This is deliberate: matching
 * an implementation to a dependency is done by exact equality of the two annotation instances, so
 * partial/wildcard attributes would silently fail to match rather than resolving as "don't care".
 *
 * <p>Meta-annotated with {@code @Qualifier} — required for Spring's
 * {@code QualifierAnnotationAutowireCandidateResolver} to recognize this as a qualifier type at
 * all; without it, an injection point's {@code @ServiceVersion} is silently ignored and normal
 * (non-qualified) autowiring rules apply instead. This is the one place {@code modular-core}
 * depends on Spring — {@code spring-beans} only, not the full framework.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
@Qualifier
public @interface ServiceVersion {

    /** The {@code @ModularService} interface this version belongs to. */
    Class<?> value();

    /** The version string, matched exactly against the interface's declared/configured versions. */
    String version();
}
