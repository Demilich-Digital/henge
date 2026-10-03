package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
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

        assertThat(properties.service("audit-service").resolveMode(1)).isEqualTo("internal-rest");
    }

    @Test
    void resolveUrlIsReachableFromAPerVersionSystemEnvironmentStyleVariable() {
        ModularProperties properties = new ModularProperties(
                environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL", "http://audit-v2:8080"));

        assertThat(properties.service("audit-service").resolveUrl(2)).isEqualTo("http://audit-v2:8080");
    }

    @Test
    void explicitVersionsDiscoversAVersionDeclaredOnlyViaASystemEnvironmentStyleVariable() {
        ModularProperties properties = new ModularProperties(
                environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_MODE", "internal-rest"));

        assertThat(properties.service("audit-service").explicitVersions()).containsExactly(2);
    }

    @Test
    void explicitVersionsRejectsANonIntegerVersionKeyInDottedForm() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("yaml",
                Map.of("modular.services.audit-service.versions.beta.mode", "internal-rest")));
        ModularProperties properties = new ModularProperties(environment);

        assertThatThrownBy(() -> properties.service("audit-service").explicitVersions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versions must be integers");
    }

    @Test
    void explicitVersionsIgnoresAnotherServicesEnvironmentVariableThatSharesItsPrefix() {
        // Service "x-versions"'s mode, which also starts with service "x"'s MODULAR_SERVICES_X_VERSIONS_ prefix.
        ModularProperties properties = new ModularProperties(environmentWithEnvVar("MODULAR_SERVICES_X_VERSIONS_MODE", "embedded"));

        assertThat(properties.service("x").explicitVersions()).isEmpty();
    }

    @Test
    void serveAcceptsAListAsWellAsACommaSeparatedValue() {
        StandardEnvironment list = new StandardEnvironment();
        list.getPropertySources().addFirst(new MapPropertySource("yaml",
                Map.of("modular.serve[0]", "audit-service", "modular.serve[1]", "greeting-service@2")));
        assertThat(new ModularProperties(list).getServe()).containsExactly("audit-service", "greeting-service@2");

        StandardEnvironment scalar = new StandardEnvironment();
        scalar.getPropertySources().addFirst(new MapPropertySource("cli", Map.of("modular.serve", "audit-service, greeting-service")));
        assertThat(new ModularProperties(scalar).getServe()).containsExactly("audit-service", "greeting-service");
    }

    @Test
    void theHigherPrecedenceFormOfServeWinsOutright() {
        Map<String, Object> listForm = Map.of("modular.serve[0]", "audit-service");
        Map<String, Object> scalarForm = Map.of("modular.serve", "greeting-service");

        StandardEnvironment scalarFirst = new StandardEnvironment();
        scalarFirst.getPropertySources().addFirst(new MapPropertySource("yaml", listForm));
        scalarFirst.getPropertySources().addFirst(new MapPropertySource("cli", scalarForm));
        assertThat(new ModularProperties(scalarFirst).getServe()).containsExactly("greeting-service");

        StandardEnvironment listFirst = new StandardEnvironment();
        listFirst.getPropertySources().addFirst(new MapPropertySource("defaults", scalarForm));
        listFirst.getPropertySources().addFirst(new MapPropertySource("yaml", listForm));
        assertThat(new ModularProperties(listFirst).getServe()).containsExactly("audit-service");
    }

    private static ModularProperties withReadTimeout(String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("modular.transport.read-timeout", value)));
        return new ModularProperties(environment);
    }

    @Test
    void timeoutsAcceptMillisecondsUnitSuffixesAndIso() {
        assertThat(withReadTimeout("2500").getReadTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(withReadTimeout("3s").getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(withReadTimeout("PT1M").getReadTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new ModularProperties(new StandardEnvironment()).getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new ModularProperties(new StandardEnvironment()).getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void negativeOrUnparseableTimeoutsFailNamingTheProperty() {
        assertThatThrownBy(() -> withReadTimeout("-5").getReadTimeout())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modular.transport.read-timeout=-5 is negative");
        assertThatThrownBy(() -> withReadTimeout("soon").getReadTimeout())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modular.transport.read-timeout=soon is not a duration");
    }

    @Test
    void pathPrefixMustStartWithASlashAndNotEndWithOne() {
        assertThat(new ModularProperties(new StandardEnvironment()).getServerPathPrefix()).isEqualTo("/_modular");
        for (String bad : new String[] {"_modular", "/_modular/", "/", ""}) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("modular.server.path-prefix", bad)));
            assertThatThrownBy(() -> new ModularProperties(environment).getServerPathPrefix())
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must start with '/' and not end with one");
        }
    }

    private static ModularProperties withProperties(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return new ModularProperties(environment);
    }

    @Test
    void validServicePropertiesAreNotReported() {
        ModularProperties properties = withProperties(Map.of(
                "modular.services.audit-service.mode", "internal-rest",
                "modular.services.audit-service.url", "http://audit",
                "modular.services.audit-service.versions.2.mode", "embedded",
                "modular.services.audit-service.versions.2.url", "http://audit-v2"));

        assertThat(properties.unknownServiceProperties(Set.of("audit-service"))).isEmpty();
    }

    @Test
    void aMisspelledKeyOrServiceNameIsReported() {
        ModularProperties properties = withProperties(Map.of(
                "modular.services.audit-service.mdoe", "internal-rest",
                "modular.services.audit-servce.mode", "internal-rest",
                "modular.services.audit-service.versions.2.uri", "http://audit-v2"));

        assertThat(properties.unknownServiceProperties(Set.of("audit-service", "greeting-service")))
                .anySatisfy(problem -> assertThat(problem).startsWith("modular.services.audit-service.mdoe: not a known key"))
                .anySatisfy(problem -> assertThat(problem).startsWith("modular.services.audit-servce.mode")
                        .contains("did you mean 'audit-service'?"))
                .anySatisfy(problem -> assertThat(problem).startsWith("modular.services.audit-service.versions.2.uri: not a known key"))
                .hasSize(3);
    }

    @Test
    void environmentVariablesAreCheckedAgainstEveryDiscoveredName() {
        assertThat(new ModularProperties(environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL", "http://a"))
                .unknownServiceProperties(Set.of("audit-service"))).isEmpty();
        // Ambiguous by construction, but valid as service "x-versions"'s mode.
        assertThat(new ModularProperties(environmentWithEnvVar("MODULAR_SERVICES_X_VERSIONS_MODE", "embedded"))
                .unknownServiceProperties(Set.of("x", "x-versions"))).isEmpty();
        assertThat(new ModularProperties(environmentWithEnvVar("MODULAR_SERVICES_AUDIT_SERVICE_MDOE", "embedded"))
                .unknownServiceProperties(Set.of("audit-service")))
                .singleElement().asString().startsWith("MODULAR_SERVICES_AUDIT_SERVICE_MDOE: doesn't match");
    }
}
