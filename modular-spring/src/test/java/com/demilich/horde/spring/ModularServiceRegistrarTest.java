package com.demilich.horde.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Direct unit test for {@link ModularServiceRegistrar#resolveClass}: a class found by classpath
 * scanning that then fails to load must fail startup loudly, not vanish silently (worst case
 * under Spring Boot DevTools' restart classloader, where an entire batch could vanish at once).
 */
class ModularServiceRegistrarTest {

    @Test
    void resolveClassFailsFastForAnUnloadableCandidate() {
        assertThatThrownBy(() -> ModularServiceRegistrar.resolveClass(
                        "com.demilich.horde.spring.DoesNotExist", getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.demilich.horde.spring.DoesNotExist")
                .hasMessageContaining("ClassNotFoundException");
    }

    @Test
    void resolveClassSucceedsForALoadableCandidate() {
        assertThat(ModularServiceRegistrar.resolveClass(getClass().getName(), getClass().getClassLoader()))
                .isEqualTo(ModularServiceRegistrarTest.class);
    }
}
