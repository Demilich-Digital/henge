package digital.demilich.henge.spring;

import java.util.Locale;

/**
 * How a given {@code @HengeService} is fulfilled in this process.
 */
public enum HengeMode {

    /** The local {@code @ServiceVersion} implementation handles the call directly, in-process. */
    EMBEDDED,

    /** The call is dispatched over HTTP to another process's {@link #EMBEDDED} instance of this service. */
    INTERNAL_REST;

    static HengeMode parse(String raw, String serviceName) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown mode '" + raw + "' for Henge service '" + serviceName
                    + "' (expected 'embedded' or 'internal-rest')");
        }
    }
}
