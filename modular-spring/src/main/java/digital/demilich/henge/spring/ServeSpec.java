package digital.demilich.henge.spring;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parsed form of {@code modular.serve} — entries are {@code name} (matches the service at any
 * version) or {@code name@version} (matches only that exact version). Used by
 * {@link ModularServiceRegistrar} to decide, per (service, version), whether the contextual
 * default mode should be {@code embedded} or {@code internal-rest}.
 */
class ServeSpec {

    private record Entry(String name, String version) {
    }

    private final Set<Entry> entries;

    private ServeSpec(Set<Entry> entries) {
        this.entries = entries;
    }

    static ServeSpec parse(List<String> raw) {
        Set<Entry> entries = new LinkedHashSet<>();
        for (String token : raw) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int at = trimmed.indexOf('@');
            if (at < 0) {
                entries.add(new Entry(trimmed, null));
                continue;
            }
            String name = trimmed.substring(0, at).trim();
            String version = trimmed.substring(at + 1).trim();
            if (name.isEmpty() || version.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid modular.serve entry '" + token + "' -- expected 'name' or 'name@version'");
            }
            entries.add(new Entry(name, version));
        }
        return new ServeSpec(entries);
    }

    /** True if {@code modular.serve} wasn't set at all -- callers should fall back to today's unconditional default. */
    boolean isEmpty() {
        return entries.isEmpty();
    }

    boolean matches(String name, String version) {
        for (Entry entry : entries) {
            if (entry.name().equals(name) && (entry.version() == null || entry.version().equals(version))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explicit (non-wildcard) versions named for this service, e.g. {@code ["2"]} for
     * {@code audit-service@2} — so a serve-listed version that isn't otherwise discovered (no
     * local impl, no other config mentioning it) still gets processed and fails fast with a clear
     * error rather than silently doing nothing.
     */
    Set<String> versionsFor(String name) {
        return entries.stream()
                .filter(entry -> entry.name().equals(name) && entry.version() != null)
                .map(Entry::version)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
