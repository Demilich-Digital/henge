package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ServiceMethod;
import digital.demilich.henge.spring.fixture.hidden.HiddenFixtures;
import org.junit.jupiter.api.Test;

class ModularServiceDescriptorTest {

    interface Overloaded {
        void save(String name);

        void save(String name, int age);
    }

    interface OverloadedWithRename {
        void save(String name);

        @ServiceMethod(name = "saveWithAge")
        void save(String name, int age);
    }

    @Test
    void overloadedMethodsAreRejected() {
        assertThatThrownBy(() -> ModularServiceDescriptor.of("widget-service", 1, Overloaded.class, "widget-service-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overloaded methods are not supported");
    }

    @Test
    void serviceMethodNameDisambiguatesOverloads() {
        ModularServiceDescriptor descriptor =
                ModularServiceDescriptor.of("widget-service", 1, OverloadedWithRename.class, "widget-service-1");

        assertThat(descriptor.methods()).containsOnlyKeys("save", "saveWithAge");
    }

    @Test
    void methodsOfANonPublicInterfaceAreInvocableFromAnotherPackage() throws Exception {
        ModularServiceDescriptor descriptor =
                ModularServiceDescriptor.of("hidden-service", 1, HiddenFixtures.interfaceType(), "hidden-service-1");

        assertThat(descriptor.methods().get("hello").invoke(HiddenFixtures.newImpl())).isEqualTo("hello");
    }
}
