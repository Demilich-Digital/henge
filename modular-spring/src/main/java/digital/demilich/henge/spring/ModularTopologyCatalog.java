package digital.demilich.henge.spring;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/**
 * What {@link ModularServiceRegistrar} decided about every service version in play, kept for
 * {@link ModularTopologyReport}: the facts that are fixed once the process has started (how each
 * version was configured, and why), as opposed to the ones that change while it runs (leases granted,
 * who advertises what).
 */
final class ModularTopologyCatalog {

    /** Where a version's mode came from: its own configuration, {@code modular.serve}, or the embedded default. */
    enum ModeSource {
        EXPLICIT, SERVE, DEFAULT;

        /** As the JSON writes it: {@code explicit}, {@code serve}, {@code default}. */
        @JsonValue
        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A lease an implementation declares; amount and capacity are {@code null} where the configuration doesn't give them. */
    record LeaseDeclaration(String lease, Integer amount, Integer capacity) {
    }

    record Entry(
            String name,
            int version,
            String interfaceName,
            boolean defaultVersion,
            ModularMode mode,
            ModeSource modeSource,
            String implClass,
            String beanName,
            List<LeaseDeclaration> leases,
            String configuredUrl) {
    }

    private final List<Entry> entries;

    ModularTopologyCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    List<Entry> entries() {
        return entries;
    }
}
