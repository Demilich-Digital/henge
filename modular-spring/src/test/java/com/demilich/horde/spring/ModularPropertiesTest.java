package com.demilich.horde.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Verifies {@code modular.*} config is reachable from a real OS-environment-variable property
 * source, which uses {@code MODULAR_SERVICES_AUDIT_SERVICE_MODE}-style keys, not the dotted-kebab
 * form used by config files/command-line args — the concrete requirement behind "the Kubernetes
 * positioning" (see {@code next_phase.md} 1.6). Uses a real {@link SystemEnvironmentPropertySource}
 * (not {@code MockEnvironment}, whose default property source doesn't have this translation
 * behavior at all) so this is testing real Spring Framework behavior, not a mock's approximation.
 */
class ModularPropertiesTest {

    private static StandardEnvironment environmentWithEnvVar(String envVarName, String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(envVarName, value)));
        return environment;
    }

    @Test
    void resolveModeIsReachableFromASystemEnvironmentStyleVariable() {
        ModularProperties properties = new ModularProperties(
                environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_MODE", "internal-rest"));

        assertThat(properties.service("audit-service").resolveMode("1")).isEqualTo("internal-rest");
    }

    @Test
    void resolveUrlIsReachableFromAPerVersionSystemEnvironmentStyleVariable() {
        ModularProperties properties = new ModularProperties(
                environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL", "http://audit-v2:8080"));

        assertThat(properties.service("audit-service").resolveUrl("2")).isEqualTo("http://audit-v2:8080");
    }

    @Test
    void explicitVersionsDiscoversAVersionDeclaredOnlyViaASystemEnvironmentStyleVariable() {
        ModularProperties properties = new ModularProperties(
                environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_MODE", "internal-rest"));

        assertThat(properties.service("audit-service").explicitVersions()).containsExactly("2");
    }
}
