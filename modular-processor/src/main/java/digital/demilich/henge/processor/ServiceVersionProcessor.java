package digital.demilich.henge.processor;

import digital.demilich.henge.core.AddedIn;
import digital.demilich.henge.core.DeprecatedSince;
import digital.demilich.henge.core.ModularService;
import digital.demilich.henge.core.ServiceMethod;
import digital.demilich.henge.core.ServiceVersion;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Compile-time support for {@link AddedIn} / {@link DeprecatedSince}:
 *
 * <ul>
 *   <li>For every {@code @ModularService} interface with at least one {@code @AddedIn}/
 *       {@code @DeprecatedSince} method, generates a companion {@code {Interface}Skeleton}
 *       abstract class implementing the interface with a throwing override of every such
 *       method — an implementation extends the skeleton instead of implementing the interface
 *       directly, and only needs to override the methods actually in range for its declared
 *       version.</li>
 *   <li>For every {@code @ServiceVersion} implementation, validates that every interface method
 *       whose version range includes the implementation's declared version is genuinely
 *       overridden, not silently left to the generated throwing stub.</li>
 *   <li>For every {@code @ModularService} interface, rejects shapes that can't behave the same
 *       embedded and split: overloaded RPC names, static methods, inverted version ranges, and a
 *       non-interface target. All of these checks, and the ones below, cover inherited
 *       superinterface methods too — exactly the methods the runtime dispatcher exposes.</li>
 *   <li>For every {@code @ModularService} interface, rejects checked exceptions in method
 *       {@code throws} clauses and rejects parameter/return types that aren't guaranteed-value
 *       boundary types (records, enums, primitives, well-known immutable value types, or
 *       ImmutableList/ImmutableSet/ImmutableMap/Optional thereof) — see {@link #validateNoCheckedExceptions} and
 *       {@link #validateBoundaryTypes}.</li>
 * </ul>
 */
@SupportedAnnotationTypes({"digital.demilich.henge.core.ModularService", "digital.demilich.henge.core.ServiceVersion"})
public class ServiceVersionProcessor extends AbstractProcessor {

    /**
     * Immutable value types allowed as leaves at a {@code @ModularService} boundary, beyond
     * primitives/records/enums — chosen because they serialize losslessly and can't be mutated
     * after construction, so embedded and internal-rest dispatch can never observe them
     * differently.
     */
    private static final Set<String> ALLOWED_LEAF_TYPES = Set.of(
            Boolean.class.getName(), Byte.class.getName(), Short.class.getName(), Character.class.getName(),
            Integer.class.getName(), Long.class.getName(), Float.class.getName(), Double.class.getName(),
            String.class.getName(),
            "java.util.UUID",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.Duration", "java.time.Period",
            "java.time.Year", "java.time.YearMonth", "java.time.MonthDay",
            "java.time.ZoneId", "java.time.ZoneOffset", "java.time.DayOfWeek", "java.time.Month");

    /**
     * {@code java.util.List}/{@code Set}/{@code Map} are deliberately NOT in this allowlist:
     * Jackson deserializes them to a mutable {@code ArrayList}/{@code HashMap} by default, and
     * nothing stops a caller from mutating one it holds after an embedded call returns it by
     * reference -- exactly the aliasing divergence between embedded and internal-rest dispatch
     * this whole check exists to close. {@link digital.demilich.henge.core.ImmutableList} /
     * {@link digital.demilich.henge.core.ImmutableSet} / {@link digital.demilich.henge.core.ImmutableMap}
     * are the accepted collection boundary types instead. Guava's {@code ImmutableList} /
     * {@code ImmutableSet} / {@code ImmutableMap} are accepted too -- matched by FQN string, like
     * the {@code @Entity} check, so the processor doesn't need a Guava dependency to recognize
     * them; a consumer that already depends on Guava doesn't have to introduce a second immutable
     * collection type just to satisfy this rule.
     */
    private static final String IMMUTABLE_LIST = "digital.demilich.henge.core.ImmutableList";
    private static final String IMMUTABLE_SET = "digital.demilich.henge.core.ImmutableSet";
    private static final String IMMUTABLE_MAP = "digital.demilich.henge.core.ImmutableMap";
    private static final String GUAVA_IMMUTABLE_LIST = "com.google.common.collect.ImmutableList";
    private static final String GUAVA_IMMUTABLE_SET = "com.google.common.collect.ImmutableSet";
    private static final String GUAVA_IMMUTABLE_MAP = "com.google.common.collect.ImmutableMap";

    private static final Set<String> SINGLE_ARG_CONTAINERS =
            Set.of("java.util.Optional", IMMUTABLE_LIST, IMMUTABLE_SET, GUAVA_IMMUTABLE_LIST, GUAVA_IMMUTABLE_SET);

    /** Guards against non-regular generic records (e.g. {@code R<T>(R<R<T>> x)}) that would expand forever. */
    private static final int MAX_TYPE_NESTING = 32;

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private Types typeUtils;
    /** Skeleton qualified name -> the interface it was generated for, to catch two interfaces colliding on one name. */
    private final Map<String, String> generatedSkeletons = new LinkedHashMap<>();
    private final List<String> pendingValidations = new ArrayList<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ModularService.class)) {
            if (element.getKind() == ElementKind.INTERFACE) {
                TypeElement interfaceElement = (TypeElement) element;
                generateSkeletonIfNeeded(interfaceElement);
                validateMethodShapes(interfaceElement);
                validateVersionRanges(interfaceElement);
                validateNoCheckedExceptions(interfaceElement);
                validateBoundaryTypes(interfaceElement);
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@ModularService can only be applied to an interface, but " + element + " is a "
                                + element.getKind().toString().toLowerCase().replace('_', ' ')
                                + " -- it would silently never be registered as a service.",
                        element);
            }
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(ServiceVersion.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue; // an injection-site use (field/parameter), not an implementation
            }
            if (validateImplementationShape(typeElement)) {
                // Deferred to the final round: an impl extending a just-generated skeleton
                // (written this round, but not parsed/resolved until the next one) doesn't have
                // a fully resolved supertype chain yet, which makes Elements.overrides()
                // unreliable here. By processingOver(), every generated source from every
                // earlier round has been compiled and resolved.
                pendingValidations.add(((TypeElement) element).getQualifiedName().toString());
            }
        }

        if (roundEnv.processingOver()) {
            for (String qualifiedName : pendingValidations) {
                TypeElement implElement = elementUtils.getTypeElement(qualifiedName);
                if (implElement != null) {
                    validateImplementation(implElement);
                }
            }
        }
        return false;
    }

    private void generateSkeletonIfNeeded(TypeElement interfaceElement) {
        List<ExecutableElement> versionedMethods = serviceMethods(interfaceElement).stream()
                .filter(m -> !m.getModifiers().contains(Modifier.STATIC))
                .filter(m -> !hasTypeParameters(m)) // rejected by validateMethodShapes; stubs would not compile
                .filter(m -> m.getAnnotation(AddedIn.class) != null || m.getAnnotation(DeprecatedSince.class) != null)
                .toList();
        if (versionedMethods.isEmpty()) {
            return;
        }

        String packageName = elementUtils.getPackageOf(interfaceElement).getQualifiedName().toString();
        String skeletonSimpleName = interfaceElement.getSimpleName() + "Skeleton";
        String qualifiedSkeletonName = skeletonQualifiedName(interfaceElement);

        String previousOwner = generatedSkeletons.putIfAbsent(qualifiedSkeletonName, interfaceElement.getQualifiedName().toString());
        if (previousOwner != null) {
            if (!previousOwner.equals(interfaceElement.getQualifiedName().toString())) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Cannot generate " + qualifiedSkeletonName + " for " + interfaceElement.getQualifiedName()
                                + ": that skeleton name was already generated for " + previousOwner
                                + ". Give the two @ModularService interfaces distinct simple names.",
                        interfaceElement);
            }
            return;
        }

        StringBuilder src = new StringBuilder();
        if (!packageName.isEmpty()) {
            src.append("package ").append(packageName).append(";\n\n");
        }
        src.append("// Generated by modular-processor from @AddedIn/@DeprecatedSince methods on ")
                .append(interfaceElement.getQualifiedName()).append(" -- do not edit.\n");
        src.append("public abstract class ").append(skeletonSimpleName)
                .append(" implements ").append(interfaceElement.getQualifiedName()).append(" {\n\n");

        for (ExecutableElement method : versionedMethods) {
            appendStubMethod(src, method);
        }

        src.append("}\n");

        try {
            JavaFileObject file = filer.createSourceFile(qualifiedSkeletonName, interfaceElement);
            try (PrintWriter writer = new PrintWriter(file.openWriter())) {
                writer.print(src);
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate " + qualifiedSkeletonName + ": " + e.getMessage(), interfaceElement);
        }
    }

    private void appendStubMethod(StringBuilder src, ExecutableElement method) {
        src.append("    @Override\n");
        src.append("    public ").append(method.getReturnType()).append(" ").append(method.getSimpleName()).append("(");

        List<? extends VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                src.append(", ");
            }
            VariableElement param = params.get(i);
            src.append(param.asType()).append(" ").append(param.getSimpleName());
        }
        src.append(")");

        List<? extends TypeMirror> thrownTypes = method.getThrownTypes();
        if (!thrownTypes.isEmpty()) {
            src.append(" throws ");
            for (int i = 0; i < thrownTypes.size(); i++) {
                if (i > 0) {
                    src.append(", ");
                }
                src.append(thrownTypes.get(i));
            }
        }

        src.append(" {\n");
        src.append("        throw digital.demilich.henge.core.ServiceVersionSupport.unsupported(this, \"")
                .append(method.getSimpleName()).append("\", \"").append(describeRequirement(method)).append("\");\n");
        src.append("    }\n\n");
    }

    private static String describeRequirement(ExecutableElement method) {
        AddedIn addedIn = method.getAnnotation(AddedIn.class);
        DeprecatedSince deprecatedSince = method.getAnnotation(DeprecatedSince.class);
        if (addedIn != null && deprecatedSince != null) {
            return "version >= " + addedIn.value() + " and < " + deprecatedSince.value();
        } else if (addedIn != null) {
            return "version >= " + addedIn.value();
        } else {
            return "version < " + deprecatedSince.value();
        }
    }

    /**
     * Every method the runtime dispatcher exposes for {@code interfaceElement}: its own and its
     * superinterfaces' (the runtime uses {@code Class.getMethods()}), minus {@code Object}'s and
     * private interface methods. Every check below runs over this set, so a method can't escape
     * validation just by being declared on a parent interface.
     */
    private List<ExecutableElement> serviceMethods(TypeElement interfaceElement) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elementUtils.getAllMembers(interfaceElement))) {
            Element owner = method.getEnclosingElement();
            if (owner instanceof TypeElement ownerType && ownerType.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
            }
            if (method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            methods.add(method);
        }
        return methods;
    }

    private static String rpcName(ExecutableElement method) {
        ServiceMethod override = method.getAnnotation(ServiceMethod.class);
        return (override != null && !override.name().isBlank()) ? override.name() : method.getSimpleName().toString();
    }

    /**
     * Static methods would be exposed as RPC methods but have no instance to dispatch on in any
     * meaningful sense, and two methods resolving to one RPC name (overloads) can't be told apart
     * in {@code POST .../{method}} -- the runtime rejects the latter only at startup, and only in
     * a process that embeds the service.
     */
    private void validateMethodShapes(TypeElement interfaceElement) {
        if (!interfaceElement.getTypeParameters().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    interfaceElement.getQualifiedName() + " declares type parameters; a @ModularService interface can't be "
                            + "generic: nothing at runtime knows what the type variable stands for, so values of that type "
                            + "would be bound as untyped JSON maps once the service is split.",
                    interfaceElement);
        }
        Map<String, ExecutableElement> byRpcName = new LinkedHashMap<>();
        for (ExecutableElement method : serviceMethods(interfaceElement)) {
            if (!method.getTypeParameters().isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Method " + describeMethod(method) + " declares its own type parameters; @ModularService methods "
                                + "can't be generic -- a type variable is bound as an untyped JSON map once the service "
                                + "is split. Use a concrete type.",
                        method);
            } else if (method.getEnclosingElement() instanceof TypeElement owner
                    && owner != interfaceElement && !owner.getTypeParameters().isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Method " + describeMethod(method) + " is inherited from the generic interface " + owner.getQualifiedName()
                                + "; the runtime binds it against the unresolved type variable, not " + interfaceElement.getQualifiedName()
                                + "'s type argument. Declare the method concretely on the @ModularService interface instead.",
                        method);
            }
            if (method.getModifiers().contains(Modifier.STATIC)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Method '" + method.getSimpleName() + "' on " + method.getEnclosingElement()
                                + " is static; @ModularService interfaces may only declare instance methods "
                                + "(a static method would be exposed as an RPC method with nothing to dispatch to).",
                        method);
                continue;
            }
            String rpcName = rpcName(method);
            ExecutableElement existing = byRpcName.putIfAbsent(rpcName, method);
            if (existing != null && !elementUtils.overrides(method, existing, interfaceElement)
                    && !elementUtils.overrides(existing, method, interfaceElement)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Methods " + describeMethod(existing) + " and " + describeMethod(method) + " both resolve to the RPC name '"
                                + rpcName + "' on " + interfaceElement.getQualifiedName() + "; overloaded methods are not "
                                + "supported -- rename one or give it @ServiceMethod(name = ...).",
                        method);
            }
        }
    }

    /** Whether the method's signature involves a type variable the runtime can't resolve (already reported). */
    private static boolean hasTypeParameters(ExecutableElement method) {
        if (!method.getTypeParameters().isEmpty()) {
            return true;
        }
        return method.getEnclosingElement() instanceof TypeElement owner && !owner.getTypeParameters().isEmpty();
    }

    private static String describeMethod(ExecutableElement method) {
        return method.getEnclosingElement() + "#" + method;
    }

    /**
     * A range whose start isn't before its end can never be in force, which silently makes the
     * method uncallable on every version; non-positive versions can't match any {@code @ServiceVersion}
     * a sensible deployment declares.
     */
    private void validateVersionRanges(TypeElement interfaceElement) {
        for (ExecutableElement method : serviceMethods(interfaceElement)) {
            AddedIn addedIn = method.getAnnotation(AddedIn.class);
            DeprecatedSince deprecatedSince = method.getAnnotation(DeprecatedSince.class);
            if (addedIn != null && addedIn.value() < 1) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@AddedIn(" + addedIn.value() + ") on " + describeMethod(method) + " must be a positive version", method);
            }
            if (deprecatedSince != null && deprecatedSince.value() < 1) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "@DeprecatedSince(" + deprecatedSince.value() + ") on " + describeMethod(method) + " must be a positive version", method);
            }
            if (addedIn != null && deprecatedSince != null && addedIn.value() >= deprecatedSince.value()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        describeMethod(method) + " has @AddedIn(" + addedIn.value() + ") but @DeprecatedSince("
                                + deprecatedSince.value() + "): the version range is empty, so no version could ever implement it.",
                        method);
            }
        }
    }

    /**
     * The shape the runtime registrar needs from an implementation: a concrete, top-level (or
     * static nested) class or record. Anything else is silently skipped by classpath scanning, so
     * the version would just not exist at startup. Returns whether the full validation should
     * still run for this element.
     */
    private boolean validateImplementationShape(TypeElement implElement) {
        ElementKind kind = implElement.getKind();
        String problem = null;
        if (kind != ElementKind.CLASS && kind != ElementKind.RECORD) {
            problem = "it is a " + kind.toString().toLowerCase().replace('_', ' ') + ", not a class";
        } else if (implElement.getModifiers().contains(Modifier.ABSTRACT)) {
            problem = "it is abstract, so the framework can't instantiate it";
        } else if (implElement.getNestingKind() == NestingKind.MEMBER && !implElement.getModifiers().contains(Modifier.STATIC)) {
            problem = "it is a non-static inner class; make it a top-level or static nested class";
        } else if (implElement.getNestingKind() == NestingKind.LOCAL || implElement.getNestingKind() == NestingKind.ANONYMOUS) {
            problem = "it is a local/anonymous class";
        }
        if (problem != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@ServiceVersion on " + implElement.getQualifiedName() + " has no effect: " + problem
                            + ", so it would silently never be registered.",
                    implElement);
            return false;
        }
        return true;
    }

    /**
     * Embedded dispatch propagates a thrown exception as-is; internal-rest dispatch reconstructs
     * failures as {@code RuntimeException} only (see {@code RemoteExceptionReconstructor} in
     * modular-spring) — a checked exception on a {@code @ModularService} method would therefore
     * behave differently depending on deployment topology, silently. Reject it at compile time
     * instead.
     */
    private void validateNoCheckedExceptions(TypeElement interfaceElement) {
        TypeMirror runtimeExceptionType = elementUtils.getTypeElement(RuntimeException.class.getName()).asType();
        TypeMirror errorType = elementUtils.getTypeElement(Error.class.getName()).asType();

        for (ExecutableElement method : serviceMethods(interfaceElement)) {
            for (TypeMirror thrown : method.getThrownTypes()) {
                if (typeUtils.isSubtype(thrown, runtimeExceptionType) || typeUtils.isSubtype(thrown, errorType)) {
                    continue;
                }
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Method '" + method.getSimpleName() + "' on " + method.getEnclosingElement()
                                + " declares checked exception " + thrown + " in its throws clause. "
                                + "@ModularService methods must not declare checked exceptions: embedded "
                                + "dispatch would propagate it as-is, but internal-rest dispatch reconstructs "
                                + "failures as RuntimeException only, so this is a silent semantic divergence "
                                + "between the two dispatch modes. Wrap it in an unchecked exception instead.",
                        method);
            }
        }
    }

    /**
     * Enforces the state-ownership doctrine at the type level: every parameter and return type
     * reachable from a {@code @ModularService} method must be a record, enum, primitive, or a
     * well-known immutable value type, or an ImmutableList/ImmutableSet/ImmutableMap/Optional
     * thereof, recursively through
     * record components. This is strictly stronger than rejecting {@code @Entity} types alone —
     * a JPA entity can never satisfy it (mutable fields, no-arg constructor, proxying) — and it
     * makes the by-value semantics a runtime check could only approximate true by construction
     * at compile time.
     */
    private void validateBoundaryTypes(TypeElement interfaceElement) {
        for (ExecutableElement method : serviceMethods(interfaceElement)) {
            if (hasTypeParameters(method)) {
                continue; // already reported by validateMethodShapes; its type variables would only add noise
            }
            checkBoundaryType(method.getReturnType(), method, "return type", new HashSet<>());
            for (VariableElement param : method.getParameters()) {
                checkBoundaryType(param.asType(), method, "parameter '" + param.getSimpleName() + "'", new HashSet<>());
            }
        }
    }

    private void checkBoundaryType(TypeMirror type, ExecutableElement method, String position, Set<String> visiting) {
        switch (type.getKind()) {
            case VOID, BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> {
                // primitives are always safe: no aliasing, no serialization ambiguity
            }
            case ARRAY -> reportBoundaryError(method, position, type,
                    "arrays are mutable and alias across the embedded/internal-rest boundary; use List<T> instead");
            case DECLARED -> checkDeclaredBoundaryType((DeclaredType) type, method, position, visiting);
            case TYPEVAR -> reportBoundaryError(method, position, type,
                    "a type variable has no concrete type at runtime, so it would be bound as an untyped JSON map");
            case WILDCARD -> {
                // "? extends X" is bound as X at runtime, so X is what has to be valid; an unbounded or
                // lower-bounded wildcard says nothing usable about the actual type.
                TypeMirror upper = ((WildcardType) type).getExtendsBound();
                if (upper == null) {
                    reportBoundaryError(method, position, type,
                            "an unbounded or lower-bounded wildcard has no usable upper bound at runtime; use '? extends X'");
                } else {
                    checkBoundaryType(upper, method, position + "'s wildcard bound", visiting);
                }
            }
            default -> {
                // NONE, NULL, etc. -- not a real signature type
            }
        }
    }

    private void checkDeclaredBoundaryType(DeclaredType type, ExecutableElement method, String position, Set<String> visiting) {
        TypeElement typeElement = (TypeElement) type.asElement();
        String qualifiedName = typeElement.getQualifiedName().toString();

        if (ALLOWED_LEAF_TYPES.contains(qualifiedName) || typeElement.getKind() == ElementKind.ENUM) {
            return;
        }

        List<? extends TypeMirror> typeArgs = type.getTypeArguments();
        if (SINGLE_ARG_CONTAINERS.contains(qualifiedName) && typeArgs.size() == 1) {
            checkBoundaryType(typeArgs.get(0), method, position + "'s type argument", visiting);
            return;
        }
        if ((IMMUTABLE_MAP.equals(qualifiedName) || GUAVA_IMMUTABLE_MAP.equals(qualifiedName)) && typeArgs.size() == 2) {
            checkBoundaryType(typeArgs.get(0), method, position + "'s key type", visiting);
            checkBoundaryType(typeArgs.get(1), method, position + "'s value type", visiting);
            return;
        }

        // Keyed by the full parameterized type, not just the class: Box<Box<List<String>>> must still
        // validate the inner Box<List<String>> even though a Box is already being validated.
        String key = type.toString();
        if (!visiting.add(key)) {
            return; // already validating this exact type along this path -- cycle, not a violation
        }
        try {
            if (visiting.size() > MAX_TYPE_NESTING) {
                reportBoundaryError(method, position, type, "it is nested more than " + MAX_TYPE_NESTING + " levels deep");
                return;
            }
            if (typeElement.getKind() == ElementKind.RECORD) {
                for (RecordComponentElement component : typeElement.getRecordComponents()) {
                    // Substituted through the accessor, so Box<List<String>>'s component T is seen as List<String>.
                    TypeMirror componentType = ((ExecutableType) typeUtils.asMemberOf(type, component.getAccessor())).getReturnType();
                    checkBoundaryType(componentType, method, position + "'s component '" + component.getSimpleName() + "'", visiting);
                }
                return;
            }
            reportBoundaryError(method, position, type, describeRejectionReason(typeElement));
        } finally {
            visiting.remove(key);
        }
    }

    private static String describeRejectionReason(TypeElement typeElement) {
        boolean isEntity = typeElement.getAnnotationMirrors().stream()
                .anyMatch(m -> m.getAnnotationType().toString().equals("jakarta.persistence.Entity"));
        if (isEntity) {
            return "it's a JPA entity; boundary types must be immutable DTOs (records), never entities";
        }
        if (typeElement.getModifiers().contains(Modifier.SEALED)) {
            return "sealed interfaces can't be deserialized from JSON without type information, so they would work "
                    + "embedded and fail once the service is split; wrap the alternatives in a record instead";
        }
        return "it's not a record, enum, or a recognized immutable value type";
    }

    private void reportBoundaryError(ExecutableElement method, String position, TypeMirror type, String reason) {
        messager.printMessage(Diagnostic.Kind.ERROR,
                position + " " + type + " of method '" + method.getSimpleName() + "' on " + method.getEnclosingElement()
                        + " is not a valid @ModularService boundary type (" + reason + "). Boundary types must be "
                        + "records, enums, primitives, String, well-known immutable value types (java.time.*, UUID, "
                        + "BigDecimal, BigInteger), or ImmutableList/ImmutableSet/ImmutableMap/Optional thereof, "
                        + "recursively -- see the "
                        + "state-ownership doctrine in the README.",
                method);
    }

    private void validateImplementation(TypeElement implElement) {
        ServiceVersion annotation = implElement.getAnnotation(ServiceVersion.class);
        TypeElement interfaceElement = resolveServiceVersionInterface(implElement);
        if (interfaceElement == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Could not resolve the interface named by @ServiceVersion on " + implElement.getQualifiedName(), implElement);
            return;
        }

        if (interfaceElement.getAnnotation(ModularService.class) == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    implElement.getQualifiedName() + " is annotated @ServiceVersion(" + interfaceElement.getSimpleName()
                            + ".class, ...) but " + interfaceElement.getQualifiedName() + " is not annotated @ModularService",
                    implElement);
            return;
        }
        if (!typeUtils.isSubtype(typeUtils.erasure(implElement.asType()), typeUtils.erasure(interfaceElement.asType()))) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    implElement.getQualifiedName() + " is annotated @ServiceVersion(" + interfaceElement.getSimpleName()
                            + ".class, ...) but does not implement " + interfaceElement.getQualifiedName(),
                    implElement);
            return;
        }

        int implVersion = annotation.version();

        for (ExecutableElement interfaceMethod : serviceMethods(interfaceElement)) {
            AddedIn addedIn = interfaceMethod.getAnnotation(AddedIn.class);
            DeprecatedSince deprecatedSince = interfaceMethod.getAnnotation(DeprecatedSince.class);
            if (addedIn == null && deprecatedSince == null) {
                continue;
            }

            boolean inRange = (addedIn == null || implVersion >= addedIn.value())
                    && (deprecatedSince == null || implVersion < deprecatedSince.value());
            if (!inRange) {
                continue;
            }

            if (!isGenuinelyImplemented(implElement, interfaceElement, interfaceMethod)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        implElement.getQualifiedName() + " declares @ServiceVersion(" + interfaceElement.getSimpleName()
                                + ".class, " + annotation.version() + ") but does not implement '"
                                + interfaceMethod.getSimpleName() + "', which is required for that version ("
                                + describeRequirement(interfaceMethod) + ")",
                        implElement);
            }
        }
    }

    /**
     * Whether {@code implElement} gets a real implementation of {@code interfaceMethod} -- declared
     * on itself or inherited from any superclass -- as opposed to the generated skeleton's throwing
     * stub. Looks at the class's full member set rather than only its own declarations, so an
     * implementation that inherits the method from a shared base class (or from an earlier
     * version's implementation) isn't wrongly reported as missing it. {@code getAllMembers} lists
     * only the most-derived implementation of each method, so the one found here is the one a call
     * would actually reach.
     */
    private boolean isGenuinelyImplemented(TypeElement implElement, TypeElement interfaceElement, ExecutableElement interfaceMethod) {
        String skeletonName = skeletonQualifiedName(interfaceElement);
        for (ExecutableElement candidate : ElementFilter.methodsIn(elementUtils.getAllMembers(implElement))) {
            if (!elementUtils.overrides(candidate, interfaceMethod, implElement)) {
                continue;
            }
            boolean isGeneratedStub = candidate.getEnclosingElement() instanceof TypeElement owner
                    && owner.getQualifiedName().contentEquals(skeletonName);
            return !isGeneratedStub;
        }
        return false;
    }

    private String skeletonQualifiedName(TypeElement interfaceElement) {
        String packageName = elementUtils.getPackageOf(interfaceElement).getQualifiedName().toString();
        String simpleName = interfaceElement.getSimpleName() + "Skeleton";
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * Reads the {@code Class<?>}-typed {@code value()} attribute of {@code @ServiceVersion} via
     * its {@link AnnotationMirror} rather than calling {@code annotation.value()} directly, which
     * would throw {@code MirroredTypeException} during processing.
     */
    private TypeElement resolveServiceVersionInterface(TypeElement implElement) {
        for (AnnotationMirror mirror : implElement.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(ServiceVersion.class.getName())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                    elementUtils.getElementValuesWithDefaults(mirror).entrySet()) {
                if (!entry.getKey().getSimpleName().contentEquals("value")) {
                    continue;
                }
                Object value = entry.getValue().getValue();
                if (value instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement typeElement) {
                    return typeElement;
                }
            }
        }
        return null;
    }
}
