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
 * How {@code henge.*} config is read: {@code henge.serve}'s two forms, timeouts, the path prefix,
 * unrecognized {@code henge.services} keys -- and that all of it is reachable from a real
 * OS-environment-variable property source ({@code HENGE_SERVICES_AUDIT_SERVICE_MODE}-style keys, as
 * a container orchestrator that only offers env vars would set them). Those tests use a real
 * {@link SystemEnvironmentPropertySource}, not {@code MockEnvironment}, whose default property source
 * doesn't have this translation behavior at all, so they exercise real Spring Framework behavior.
 */
class HengePropertiesTest {

    private static StandardEnvironment environmentWithEnvVar(String envVarName, String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(envVarName, value)));
        return environment;
    }

    @Test
    void resolveModeIsReachableFromASystemEnvironmentStyleVariable() {
        HengeProperties properties = new HengeProperties(
                environmentWithEnvVar("HENGE_SERVICES_AUDIT_SERVICE_MODE", "internal-rest"));

        assertThat(properties.service("audit-service").resolveMode(1)).isEqualTo("internal-rest");
    }

    @Test
    void resolveUrlIsReachableFromAPerVersionSystemEnvironmentStyleVariable() {
        HengeProperties properties = new HengeProperties(
                environmentWithEnvVar("HENGE_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL", "http://audit-v2:8080"));

        assertThat(properties.service("audit-service").resolveUrl(2)).isEqualTo("http://audit-v2:8080");
    }

    @Test
    void explicitVersionsDiscoversAVersionDeclaredOnlyViaASystemEnvironmentStyleVariable() {
        HengeProperties properties = new HengeProperties(
                environmentWithEnvVar("HENGE_SERVICES_AUDIT_SERVICE_VERSIONS_2_MODE", "internal-rest"));

        assertThat(properties.service("audit-service").explicitVersions()).containsExactly(2);
    }

    @Test
    void explicitVersionsRejectsANonIntegerVersionKeyInDottedForm() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("yaml",
                Map.of("henge.services.audit-service.versions.beta.mode", "internal-rest")));
        HengeProperties properties = new HengeProperties(environment);

        assertThatThrownBy(() -> properties.service("audit-service").explicitVersions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versions must be integers");
    }

    @Test
    void explicitVersionsIgnoresAnotherServicesEnvironmentVariableThatSharesItsPrefix() {
        // Service "x-versions"'s mode, which also starts with service "x"'s HENGE_SERVICES_X_VERSIONS_ prefix.
        HengeProperties properties = new HengeProperties(environmentWithEnvVar("HENGE_SERVICES_X_VERSIONS_MODE", "embedded"));

        assertThat(properties.service("x").explicitVersions()).isEmpty();
    }

    @Test
    void serveAcceptsAListAsWellAsACommaSeparatedValue() {
        StandardEnvironment list = new StandardEnvironment();
        list.getPropertySources().addFirst(new MapPropertySource("yaml",
                Map.of("henge.serve[0]", "audit-service", "henge.serve[1]", "greeting-service@2")));
        assertThat(new HengeProperties(list).getServe()).containsExactly("audit-service", "greeting-service@2");

        StandardEnvironment scalar = new StandardEnvironment();
        scalar.getPropertySources().addFirst(new MapPropertySource("cli", Map.of("henge.serve", "audit-service, greeting-service")));
        assertThat(new HengeProperties(scalar).getServe()).containsExactly("audit-service", "greeting-service");
    }

    @Test
    void theHigherPrecedenceFormOfServeWinsOutright() {
        Map<String, Object> listForm = Map.of("henge.serve[0]", "audit-service");
        Map<String, Object> scalarForm = Map.of("henge.serve", "greeting-service");

        StandardEnvironment scalarFirst = new StandardEnvironment();
        scalarFirst.getPropertySources().addFirst(new MapPropertySource("yaml", listForm));
        scalarFirst.getPropertySources().addFirst(new MapPropertySource("cli", scalarForm));
        assertThat(new HengeProperties(scalarFirst).getServe()).containsExactly("greeting-service");

        StandardEnvironment listFirst = new StandardEnvironment();
        listFirst.getPropertySources().addFirst(new MapPropertySource("defaults", scalarForm));
        listFirst.getPropertySources().addFirst(new MapPropertySource("yaml", listForm));
        assertThat(new HengeProperties(listFirst).getServe()).containsExactly("audit-service");
    }

    private static HengeProperties withReadTimeout(String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("henge.transport.read-timeout", value)));
        return new HengeProperties(environment);
    }

    @Test
    void timeoutsAcceptMillisecondsUnitSuffixesAndIso() {
        assertThat(withReadTimeout("2500").getReadTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(withReadTimeout("3s").getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(withReadTimeout("PT1M").getReadTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new HengeProperties(new StandardEnvironment()).getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new HengeProperties(new StandardEnvironment()).getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void negativeOrUnparseableTimeoutsFailNamingTheProperty() {
        assertThatThrownBy(() -> withReadTimeout("-5").getReadTimeout())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("henge.transport.read-timeout=-5 is negative");
        assertThatThrownBy(() -> withReadTimeout("soon").getReadTimeout())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("henge.transport.read-timeout=soon is not a duration");
    }

    @Test
    void pathPrefixMustStartWithASlashAndNotEndWithOne() {
        assertThat(new HengeProperties(new StandardEnvironment()).getServerPathPrefix()).isEqualTo("/_henge");
        for (String bad : new String[] {"_henge", "/_henge/", "/", ""}) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("henge.server.path-prefix", bad)));
            assertThatThrownBy(() -> new HengeProperties(environment).getServerPathPrefix())
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must start with '/' and not end with one");
        }
    }

    private static HengeProperties withProperties(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return new HengeProperties(environment);
    }

    @Test
    void validServicePropertiesAreNotReported() {
        HengeProperties properties = withProperties(Map.of(
                "henge.services.audit-service.mode", "internal-rest",
                "henge.services.audit-service.url", "http://audit",
                "henge.services.audit-service.versions.2.mode", "embedded",
                "henge.services.audit-service.versions.2.url", "http://audit-v2"));

        assertThat(properties.unknownServiceProperties(Set.of("audit-service"))).isEmpty();
    }

    @Test
    void aMisspelledKeyOrServiceNameIsReported() {
        HengeProperties properties = withProperties(Map.of(
                "henge.services.audit-service.mdoe", "internal-rest",
                "henge.services.audit-servce.mode", "internal-rest",
                "henge.services.audit-service.versions.2.uri", "http://audit-v2"));

        assertThat(properties.unknownServiceProperties(Set.of("audit-service", "greeting-service")))
                .anySatisfy(problem -> assertThat(problem).startsWith("henge.services.audit-service.mdoe: not a known key"))
                .anySatisfy(problem -> assertThat(problem).startsWith("henge.services.audit-servce.mode")
                        .contains("did you mean 'audit-service'?"))
                .anySatisfy(problem -> assertThat(problem).startsWith("henge.services.audit-service.versions.2.uri: not a known key"))
                .hasSize(3);
    }

    @Test
    void environmentVariablesAreCheckedAgainstEveryDiscoveredName() {
        assertThat(new HengeProperties(environmentWithEnvVar("HENGE_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL", "http://a"))
                .unknownServiceProperties(Set.of("audit-service"))).isEmpty();
        // Ambiguous by construction, but valid as service "x-versions"'s mode.
        assertThat(new HengeProperties(environmentWithEnvVar("HENGE_SERVICES_X_VERSIONS_MODE", "embedded"))
                .unknownServiceProperties(Set.of("x", "x-versions"))).isEmpty();
        assertThat(new HengeProperties(environmentWithEnvVar("HENGE_SERVICES_AUDIT_SERVICE_MDOE", "embedded"))
                .unknownServiceProperties(Set.of("audit-service")))
                .singleElement().asString().startsWith("HENGE_SERVICES_AUDIT_SERVICE_MDOE: doesn't match");
    }

    @Test
    void recentVersionsDefaultsToTwoAndReadsAPositiveInteger() {
        assertThat(new HengeProperties(new org.springframework.mock.env.MockEnvironment()).getRecentVersions()).isEqualTo(2);
        assertThat(new HengeProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("henge.recent-versions", "3")).getRecentVersions()).isEqualTo(3);
        assertThatThrownBy(() -> new HengeProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("henge.recent-versions", "0")).getRecentVersions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("henge.recent-versions");
    }
}
