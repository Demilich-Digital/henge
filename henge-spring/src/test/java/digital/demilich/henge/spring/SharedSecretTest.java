package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SharedSecretTest {

    private static SharedSecret secret(String configured) {
        MockEnvironment environment = new MockEnvironment();
        if (configured != null) {
            environment.setProperty("henge.transport.secret", configured);
        }
        return SharedSecret.from(new HengeProperties(environment));
    }

    @Test
    void noSecretConfiguredAcceptsEverythingAndIsNotRequired() {
        SharedSecret none = secret(null);

        assertThat(none.isRequired()).isFalse();
        assertThat(none.accepts(null)).isTrue();
        assertThat(none.accepts("anything")).isTrue();
    }

    @Test
    void blankSecretCountsAsNoSecret() {
        assertThat(secret("   ").isRequired()).isFalse();
    }

    @Test
    void configuredSecretAcceptsOnlyAnExactMatch() {
        SharedSecret configured = secret("s3cr3t");

        assertThat(configured.isRequired()).isTrue();
        assertThat(configured.accepts("s3cr3t")).isTrue();
        assertThat(configured.accepts("S3CR3T")).isFalse();
        assertThat(configured.accepts("s3cr3")).isFalse();
        assertThat(configured.accepts("s3cr3tt")).isFalse();
        assertThat(configured.accepts("")).isFalse();
        assertThat(configured.accepts(null)).isFalse();
    }
}
