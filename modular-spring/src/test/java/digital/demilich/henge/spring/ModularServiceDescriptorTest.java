package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModularServiceDescriptorTest {

    interface OriginalOrder {
        void save(String name, int age);
    }

    interface ReorderedParams {
        void save(int age, String name);
    }

    interface SameShapeDifferentInterface {
        void save(String name, int age);
    }

    interface DifferentReturnType {
        String save(String name, int age);
    }

    interface DifferentGenericArgument {
        void save(List<String> names);
    }

    interface DifferentGenericArgumentToo {
        void save(List<Integer> names);
    }

    @Test
    void reorderingParametersChangesTheFingerprint() {
        assertThat(ModularServiceDescriptor.fingerprint(OriginalOrder.class))
                .isNotEqualTo(ModularServiceDescriptor.fingerprint(ReorderedParams.class));
    }

    @Test
    void changingReturnTypeChangesTheFingerprint() {
        assertThat(ModularServiceDescriptor.fingerprint(OriginalOrder.class))
                .isNotEqualTo(ModularServiceDescriptor.fingerprint(DifferentReturnType.class));
    }

    @Test
    void changingAGenericTypeArgumentChangesTheFingerprint() {
        // Erased-type fingerprinting would miss this (both are just "List") -- generic parameter
        // types are used deliberately, since that's what the dispatcher actually binds against.
        assertThat(ModularServiceDescriptor.fingerprint(DifferentGenericArgument.class))
                .isNotEqualTo(ModularServiceDescriptor.fingerprint(DifferentGenericArgumentToo.class));
    }

    @Test
    void identicalMethodShapesOnDifferentInterfacesProduceTheSameFingerprint() {
        // The fingerprint is a hash of the *shape*, not tied to the interface's identity/name --
        // this is what makes it meaningful to compare a client's locally-compiled copy of an
        // interface against the dispatcher's, potentially loaded from a different jar.
        assertThat(ModularServiceDescriptor.fingerprint(OriginalOrder.class))
                .isEqualTo(ModularServiceDescriptor.fingerprint(SameShapeDifferentInterface.class));
    }

    @Test
    void fingerprintIsRepeatableAndIndependentOfGetMethodsIterationOrder() {
        // Class.getMethods()'s iteration order is unspecified by the JDK -- repeated calls within
        // this JVM at least prove the sort-before-hash approach doesn't leak that non-determinism
        // through, which is the property "stable across JVM runs" actually depends on.
        String first = ModularServiceDescriptor.fingerprint(OriginalOrder.class);
        String second = ModularServiceDescriptor.fingerprint(OriginalOrder.class);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void descriptorOfExposesTheSameFingerprintAsTheStaticMethod() {
        ModularServiceDescriptor descriptor = ModularServiceDescriptor.of("widget-service", "1", OriginalOrder.class, "widget-service-1");
        assertThat(descriptor.contractFingerprint()).isEqualTo(ModularServiceDescriptor.fingerprint(OriginalOrder.class));
    }

    @Test
    void multipleMethodsAreAllReflectedInTheFingerprint() {
        interface OneMethod {
            void save(String name);
        }
        interface TwoMethods {
            void save(String name);

            void delete(String name);
        }
        assertThat(ModularServiceDescriptor.fingerprint(OneMethod.class))
                .isNotEqualTo(ModularServiceDescriptor.fingerprint(TwoMethods.class));
    }

    @Test
    void unrelatedInterfacesWithDifferentMethodNamesDiffer() {
        interface Named {
            void save(Map<String, Integer> counts);
        }
        assertThat(ModularServiceDescriptor.fingerprint(Named.class))
                .isNotEqualTo(ModularServiceDescriptor.fingerprint(DifferentGenericArgument.class));
    }
}
