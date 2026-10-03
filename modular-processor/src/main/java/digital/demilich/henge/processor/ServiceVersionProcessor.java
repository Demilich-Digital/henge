package digital.demilich.henge.processor;

import digital.demilich.henge.core.AddedIn;
import digital.demilich.henge.core.DeprecatedSince;
import digital.demilich.henge.core.ModularService;
import digital.demilich.henge.core.ServiceVersion;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
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
 *   <li>For every {@code @ModularService} interface, rejects checked exceptions in method
 *       {@code throws} clauses and rejects parameter/return types that aren't guaranteed-value
 *       boundary types (records, enums, primitives, well-known immutable value types, or
 *       ImmutableList/ImmutableSet/ImmutableMap/Optional thereof) — see {@link #validateNoCheckedExceptions} and
 *       {@link #validateBoundaryTypes}.</li>
 * </ul>
 */
@SupportedAnnotationTypes({"digital.demilich.henge.core.ModularService", "digital.demilich.henge.core.ServiceVersion"})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
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

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private Types typeUtils;
    private final Set<String> generatedSkeletons = new HashSet<>();
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
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ModularService.class)) {
            if (element.getKind() == ElementKind.INTERFACE) {
                TypeElement interfaceElement = (TypeElement) element;
                generateSkeletonIfNeeded(interfaceElement);
                validateNoCheckedExceptions(interfaceElement);
                validateBoundaryTypes(interfaceElement);
            }
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(ServiceVersion.class)) {
            if (element.getKind() == ElementKind.CLASS) {
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
        List<ExecutableElement> versionedMethods = ElementFilter.methodsIn(interfaceElement.getEnclosedElements()).stream()
                .filter(m -> m.getAnnotation(AddedIn.class) != null || m.getAnnotation(DeprecatedSince.class) != null)
                .toList();
        if (versionedMethods.isEmpty()) {
            return;
        }

        String packageName = elementUtils.getPackageOf(interfaceElement).getQualifiedName().toString();
        String skeletonSimpleName = interfaceElement.getSimpleName() + "Skeleton";
        String qualifiedSkeletonName = packageName.isEmpty() ? skeletonSimpleName : packageName + "." + skeletonSimpleName;

        if (!generatedSkeletons.add(qualifiedSkeletonName)) {
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
     * Embedded dispatch propagates a thrown exception as-is; internal-rest dispatch reconstructs
     * failures as {@code RuntimeException} only (see {@code RemoteExceptionReconstructor} in
     * modular-spring) — a checked exception on a {@code @ModularService} method would therefore
     * behave differently depending on deployment topology, silently. Reject it at compile time
     * instead.
     */
    private void validateNoCheckedExceptions(TypeElement interfaceElement) {
        TypeMirror runtimeExceptionType = elementUtils.getTypeElement(RuntimeException.class.getName()).asType();
        TypeMirror errorType = elementUtils.getTypeElement(Error.class.getName()).asType();

        for (ExecutableElement method : ElementFilter.methodsIn(interfaceElement.getEnclosedElements())) {
            for (TypeMirror thrown : method.getThrownTypes()) {
                if (typeUtils.isSubtype(thrown, runtimeExceptionType) || typeUtils.isSubtype(thrown, errorType)) {
                    continue;
                }
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Method '" + method.getSimpleName() + "' on " + interfaceElement.getQualifiedName()
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
     * makes the by-value semantics that {@code modular.strict} mode only checks at runtime true
     * by construction at compile time.
     */
    private void validateBoundaryTypes(TypeElement interfaceElement) {
        for (ExecutableElement method : ElementFilter.methodsIn(interfaceElement.getEnclosedElements())) {
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
            case TYPEVAR, WILDCARD -> {
                // the concrete type isn't known from the interface alone; left unchecked
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

        if (!visiting.add(qualifiedName)) {
            return; // already validating this type along this path -- cycle, not a violation
        }
        try {
            if (typeElement.getKind() == ElementKind.RECORD) {
                for (RecordComponentElement component : typeElement.getRecordComponents()) {
                    checkBoundaryType(component.asType(), method, position + "'s component '" + component.getSimpleName() + "'", visiting);
                }
                return;
            }
            if (typeElement.getKind() == ElementKind.INTERFACE && typeElement.getModifiers().contains(Modifier.SEALED)) {
                List<? extends TypeMirror> permitted = typeElement.getPermittedSubclasses();
                if (!permitted.isEmpty()) {
                    for (TypeMirror subtype : permitted) {
                        checkBoundaryType(subtype, method, position, visiting);
                    }
                    return;
                }
            }
            reportBoundaryError(method, position, type, describeRejectionReason(typeElement));
        } finally {
            visiting.remove(qualifiedName);
        }
    }

    private static String describeRejectionReason(TypeElement typeElement) {
        boolean isEntity = typeElement.getAnnotationMirrors().stream()
                .anyMatch(m -> m.getAnnotationType().toString().equals("jakarta.persistence.Entity"));
        if (isEntity) {
            return "it's a JPA entity; boundary types must be immutable DTOs (records), never entities";
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

        int implVersion = annotation.version();

        for (ExecutableElement interfaceMethod : ElementFilter.methodsIn(interfaceElement.getEnclosedElements())) {
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

            if (!isOverriddenDirectly(implElement, interfaceMethod)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        implElement.getQualifiedName() + " declares @ServiceVersion(" + interfaceElement.getSimpleName()
                                + ".class, " + annotation.version() + ") but does not implement '"
                                + interfaceMethod.getSimpleName() + "', which is required for that version ("
                                + describeRequirement(interfaceMethod) + ")",
                        implElement);
            }
        }
    }

    private boolean isOverriddenDirectly(TypeElement implElement, ExecutableElement interfaceMethod) {
        for (ExecutableElement candidate : ElementFilter.methodsIn(implElement.getEnclosedElements())) {
            if (elementUtils.overrides(candidate, interfaceMethod, implElement)) {
                return true;
            }
        }
        return false;
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
