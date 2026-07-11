package io.modular.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServeSpecTest {

    @Test
    void emptyListIsEmpty() {
        assertThat(ServeSpec.parse(List.of()).isEmpty()).isTrue();
    }

    @Test
    void unqualifiedEntryMatchesAnyVersion() {
        ServeSpec spec = ServeSpec.parse(List.of("audit-service"));

        assertThat(spec.isEmpty()).isFalse();
        assertThat(spec.matches("audit-service", "1")).isTrue();
        assertThat(spec.matches("audit-service", "2")).isTrue();
        assertThat(spec.matches("greeting-service", "1")).isFalse();
    }

    @Test
    void qualifiedEntryMatchesOnlyItsExactVersion() {
        ServeSpec spec = ServeSpec.parse(List.of("audit-service@2"));

        assertThat(spec.matches("audit-service", "2")).isTrue();
        assertThat(spec.matches("audit-service", "1")).isFalse();
    }

    @Test
    void parsesMultipleCommaSeparatedEntries() {
        // Spring Boot binds a comma-separated CLI/property value into individual List<String>
        // elements before this ever sees them, so each element here is already one token.
        ServeSpec spec = ServeSpec.parse(List.of("audit-service@1", "greeting-service"));

        assertThat(spec.matches("audit-service", "1")).isTrue();
        assertThat(spec.matches("audit-service", "2")).isFalse();
        assertThat(spec.matches("greeting-service", "7")).isTrue();
    }

    @Test
    void versionsForReturnsOnlyExplicitlyQualifiedVersions() {
        ServeSpec spec = ServeSpec.parse(List.of("audit-service@2", "audit-service@3", "greeting-service"));

        assertThat(spec.versionsFor("audit-service")).containsExactlyInAnyOrder("2", "3");
        assertThat(spec.versionsFor("greeting-service")).isEmpty();
        assertThat(spec.versionsFor("unrelated-service")).isEmpty();
    }

    @Test
    void blankEntriesAreIgnored() {
        ServeSpec spec = ServeSpec.parse(List.of("", "  ", "audit-service"));

        assertThat(spec.matches("audit-service", "1")).isTrue();
    }

    @Test
    void malformedEntryFailsFast() {
        assertThatThrownBy(() -> ServeSpec.parse(List.of("audit-service@")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit-service@");
    }
}
