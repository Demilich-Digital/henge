package digital.demilich.henge.spring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The optional {@code henge.transport.secret}, and the one place that knows how to check it:
 * {@link HengeDispatcherController} uses it directly, and {@code henge-spring-boot-starter}'s
 * Spring Security integration uses it to authenticate callers, so the two can never disagree.
 *
 * <p>Compared with {@link MessageDigest#isEqual(byte[], byte[])}, which its Javadoc guarantees takes
 * time independent of where the arrays first differ, unlike {@link String#equals}, which would let a
 * timing attack narrow down the secret one byte at a time.
 */
final class SharedSecret {

    private final byte[] configured;

    private SharedSecret(byte[] configured) {
        this.configured = configured;
    }

    /** A blank or unset value means no secret is required (the network-isolated default). */
    static SharedSecret from(HengeProperties properties) {
        String secret = properties.getTransportSecret();
        return new SharedSecret(secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8));
    }

    boolean isRequired() {
        return configured != null;
    }

    /** Always true when no secret is configured; otherwise only for an exact match. */
    boolean accepts(String provided) {
        if (configured == null) {
            return true;
        }
        return provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), configured);
    }
}
