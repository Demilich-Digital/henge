package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import digital.demilich.henge.core.ServiceMethod;
import digital.demilich.henge.spring.fixture.hidden.HiddenFixtures;
import org.junit.jupiter.api.Test;

class HengeServiceDescriptorTest {

    interface Overloaded {
        void save(String name);

        void save(String name, int age);
    }

    interface OverloadedWithRename {
        void save(String name);

        @ServiceMethod(name = "saveWithAge")
        void save(String name, int age);
    }

    interface FromA {
        String a();
    }

    interface FromB {
        String a();
    }

    interface Diamond extends FromA, FromB {
    }

    @Test
    void theSameMethodInheritedFromTwoSuperinterfacesIsOneMethod() {
        HengeServiceDescriptor descriptor = HengeServiceDescriptor.of("diamond-service", 1, Diamond.class, "diamond-service-1");

        assertThat(descriptor.methods()).containsOnlyKeys("a");
    }

    interface WithStaticHelper {
        String a();

        static WithStaticHelper of() {
            return () -> "a";
        }
    }

    @Test
    void staticInterfaceMethodsAreNotDispatchable() {
        HengeServiceDescriptor descriptor =
                HengeServiceDescriptor.of("helper-service", 1, WithStaticHelper.class, "helper-service-1");

        assertThat(descriptor.methods()).containsOnlyKeys("a");
    }

    @Test
    void overloadedMethodsAreRejected() {
        assertThatThrownBy(() -> HengeServiceDescriptor.of("widget-service", 1, Overloaded.class, "widget-service-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overloaded methods are not supported");
    }

    @Test
    void serviceMethodNameDisambiguatesOverloads() {
        HengeServiceDescriptor descriptor =
                HengeServiceDescriptor.of("widget-service", 1, OverloadedWithRename.class, "widget-service-1");

        assertThat(descriptor.methods()).containsOnlyKeys("save", "saveWithAge");
    }

    @Test
    void methodsOfANonPublicInterfaceAreInvocableFromAnotherPackage() throws Exception {
        HengeServiceDescriptor descriptor =
                HengeServiceDescriptor.of("hidden-service", 1, HiddenFixtures.interfaceType(), "hidden-service-1");

        assertThat(descriptor.methods().get("hello").invoke(HiddenFixtures.newImpl())).isEqualTo("hello");
    }
}
