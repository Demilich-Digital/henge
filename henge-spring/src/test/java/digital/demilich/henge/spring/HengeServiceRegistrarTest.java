package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct unit test for {@link HengeServiceRegistrar#resolveClass}: a class found by classpath
 * scanning that then fails to load must fail startup loudly, not vanish silently (worst case
 * under Spring Boot DevTools' restart classloader, where an entire batch could vanish at once).
 */
class HengeServiceRegistrarTest {

    @Test
    void resolveClassFailsFastForAnUnloadableCandidate() {
        assertThatThrownBy(() -> HengeServiceRegistrar.resolveClass(
                        "digital.demilich.henge.spring.DoesNotExist", getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digital.demilich.henge.spring.DoesNotExist")
                .hasMessageContaining("ClassNotFoundException");
    }

    @Test
    void resolveClassSucceedsForALoadableCandidate() {
        assertThat(HengeServiceRegistrar.resolveClass(getClass().getName(), getClass().getClassLoader()))
                .isEqualTo(HengeServiceRegistrarTest.class);
    }

    @Test
    void recentVersionsKeepsTheHighestAndDropsOlderImplementations() {
        assertThat(HengeServiceRegistrar.recentVersions("svc", Set.of(1, 2, 3), Set.of(3), 2)).containsExactlyInAnyOrder(2, 3);
        assertThat(HengeServiceRegistrar.recentVersions("svc", Set.of(1, 2, 3), Set.of(3), 3)).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(HengeServiceRegistrar.recentVersions("svc", Set.of(4), Set.of(4), 2)).containsExactly(4);
    }

    @Test
    void aNamedVersionOutsideTheWindowFailsStartup() {
        assertThatThrownBy(() -> HengeServiceRegistrar.recentVersions("svc", Set.of(1, 2, 3), Set.of(1, 3), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("svc")
                .hasMessageContaining("version 1")
                .hasMessageContaining("henge.recent-versions");
    }
}
