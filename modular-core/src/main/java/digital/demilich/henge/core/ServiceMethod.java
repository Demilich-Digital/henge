package digital.demilich.henge.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional override for the RPC method name used in the dispatch path.
 * Defaults to {@link java.lang.reflect.Method#getName()} when absent.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServiceMethod {

    String name() default "";
}
