package digital.demilich.henge.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chooses the HTTP status an {@code internal-rest} dispatch answers with when an implementation
 * throws the annotated exception (or a subclass). Without it, a business exception is reported as
 * {@code 500} — the status this framework deliberately reserves for failures it can't classify
 * (an implementation bug, an unexpected error) — so a service can say "this was a client-side
 * mistake" ({@code 4xx}) by annotating the exception it throws:
 *
 * <pre>{@code
 * @ErrorStatus(404)
 * public class WidgetNotFoundException extends RuntimeException { ... }
 * }</pre>
 *
 * <p>How the serving process logs it is chosen separately, with {@link ErrorLogLevel}.
 *
 * <p>Only the status changes: the caller still gets the original exception type reconstructed, as
 * with any other business exception (see the README). Has no effect when the service is embedded,
 * since no HTTP response is involved. Must be a {@code 4xx} or {@code 5xx} code: {@code modular-processor}
 * rejects anything else at compile time, and the dispatcher logs and ignores it (answering
 * {@code 500}) for a class compiled without the processor.
 *
 * <p>The dispatcher itself answers {@code 400} (malformed request), {@code 403} (bad secret) and
 * {@code 404} (unknown service/version/method) for its own failures — those responses carry no
 * exception type, so they stay distinguishable from an annotated business exception even when it
 * reuses one of those codes.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ErrorStatus {

    /** The HTTP status code, {@code 400}–{@code 599}. */
    int value();
}
