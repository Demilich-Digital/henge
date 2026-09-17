package digital.demilich.henge.core;

/**
 * Called only from {@code modular-processor}-generated {@code {Interface}Skeleton} stub methods
 * — not intended to be called from hand-written code.
 */
public final class ServiceVersionSupport {

    private ServiceVersionSupport() {
    }

    public static ServiceVersionUnsupportedException unsupported(Object self, String methodName, String versionRequirement) {
        return new ServiceVersionUnsupportedException(
                self.getClass().getName() + "#" + methodName + " is not supported by this implementation (requires "
                        + versionRequirement + ")");
    }
}
