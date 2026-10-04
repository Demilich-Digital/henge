package digital.demilich.henge.core;

import java.util.regex.Pattern;

/**
 * How a {@link HengeService}'s name and its methods' RPC names are derived and what they may
 * contain -- shared by {@code henge-processor} (compile time) and {@code henge-spring}
 * (startup), so the two can never disagree. Not intended to be called from hand-written code.
 *
 * <p>A service name is a URL path segment, a {@code henge.services.<name>.*} property-key
 * segment and an environment-variable fragment all at once, so it is restricted to lowercase
 * kebab case: a {@code /} would split the dispatch path and a {@code .} the property key, and
 * {@code _} would make {@code audit_service} and {@code audit-service} the same environment
 * variable. An RPC method name defaults to the Java method name, so it may be any Java identifier.
 */
public final class ServiceNames {

    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private ServiceNames() {
    }

    /** The explicit {@code @HengeService(name = ...)} if set, else the interface's simple name in kebab case. */
    public static String serviceName(String explicitName, String interfaceSimpleName) {
        if (!explicitName.isBlank()) {
            return explicitName;
        }
        StringBuilder kebab = new StringBuilder();
        for (int i = 0; i < interfaceSimpleName.length(); i++) {
            char c = interfaceSimpleName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(c));
            } else {
                kebab.append(c);
            }
        }
        return kebab.toString();
    }

    public static boolean isValidServiceName(String name) {
        return SERVICE_NAME.matcher(name).matches();
    }

    public static boolean isValidMethodName(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    /** The error message for a service name that fails {@link #isValidServiceName}. */
    public static String invalidServiceNameMessage(String name, String interfaceName) {
        return "Henge service name '" + name + "' of " + interfaceName + " must be lowercase kebab case "
                + "([a-z0-9]+ separated by single '-') -- it is used as a URL path segment and a "
                + "henge.services.<name> property key. Set @HengeService(name = ...) explicitly.";
    }

    /** The error message for an RPC method name that fails {@link #isValidMethodName}. */
    public static String invalidMethodNameMessage(String name, String interfaceName) {
        return "RPC method name '" + name + "' on " + interfaceName + " must be a Java identifier -- it is used as "
                + "a URL path segment in the dispatch path.";
    }
}
