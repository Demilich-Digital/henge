package digital.demilich.henge.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Enables discovery and wiring of {@code @ModularService} interfaces. Place on the same class
 * as {@code @SpringBootApplication}.
 *
 * <p>Contract interfaces very often live in a separate module/package from the application
 * class (e.g. a shared {@code contracts} module), so unlike plain {@code @ComponentScan} this
 * has no implicit classpath-wide default — specify {@link #basePackages()} or
 * {@link #basePackageClasses()} whenever contracts aren't in the application class's own
 * package tree. When neither is set, scanning defaults to the annotated class's own package,
 * same as {@code @SpringBootApplication}'s default component scan.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(ModularServiceRegistrar.class)
public @interface EnableModularServices {

    String[] basePackages() default {};

    Class<?>[] basePackageClasses() default {};
}
