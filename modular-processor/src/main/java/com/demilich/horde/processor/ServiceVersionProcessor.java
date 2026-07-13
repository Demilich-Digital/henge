package com.demilich.horde.processor;

import com.demilich.horde.core.AddedIn;
import com.demilich.horde.core.DeprecatedSince;
import com.demilich.horde.core.ModularService;
import com.demilich.horde.core.ServiceVersion;
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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
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
 * </ul>
 */
@SupportedAnnotationTypes({"com.demilich.horde.core.ModularService", "com.demilich.horde.core.ServiceVersion"})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class ServiceVersionProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private final Set<String> generatedSkeletons = new HashSet<>();
    private final List<String> pendingValidations = new ArrayList<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(ModularService.class)) {
            if (element.getKind() == ElementKind.INTERFACE) {
                generateSkeletonIfNeeded((TypeElement) element);
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
        src.append("        throw com.demilich.horde.core.ServiceVersionSupport.unsupported(this, \"")
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

    private void validateImplementation(TypeElement implElement) {
        ServiceVersion annotation = implElement.getAnnotation(ServiceVersion.class);
        TypeElement interfaceElement = resolveServiceVersionInterface(implElement);
        if (interfaceElement == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Could not resolve the interface named by @ServiceVersion on " + implElement.getQualifiedName(), implElement);
            return;
        }

        Integer implVersion = parseVersionOrReportError(annotation.version(), implElement, "@ServiceVersion");
        if (implVersion == null) {
            return;
        }

        for (ExecutableElement interfaceMethod : ElementFilter.methodsIn(interfaceElement.getEnclosedElements())) {
            AddedIn addedIn = interfaceMethod.getAnnotation(AddedIn.class);
            DeprecatedSince deprecatedSince = interfaceMethod.getAnnotation(DeprecatedSince.class);
            if (addedIn == null && deprecatedSince == null) {
                continue;
            }

            Integer addedInVersion = addedIn == null ? null : parseVersionOrReportError(addedIn.value(), interfaceMethod, "@AddedIn");
            Integer deprecatedSinceVersion =
                    deprecatedSince == null ? null : parseVersionOrReportError(deprecatedSince.value(), interfaceMethod, "@DeprecatedSince");
            if ((addedIn != null && addedInVersion == null) || (deprecatedSince != null && deprecatedSinceVersion == null)) {
                continue;
            }

            boolean inRange = (addedInVersion == null || implVersion >= addedInVersion)
                    && (deprecatedSinceVersion == null || implVersion < deprecatedSinceVersion);
            if (!inRange) {
                continue;
            }

            if (!isOverriddenDirectly(implElement, interfaceMethod)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        implElement.getQualifiedName() + " declares @ServiceVersion(" + interfaceElement.getSimpleName()
                                + ".class, \"" + annotation.version() + "\") but does not implement '"
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

    private Integer parseVersionOrReportError(String value, Element element, String annotationDescription) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    annotationDescription + " version '" + value + "' on " + describe(element)
                            + " must be parseable as an integer for @AddedIn/@DeprecatedSince range checks to work",
                    element);
            return null;
        }
    }

    private static String describe(Element element) {
        return element.getKind() == ElementKind.METHOD
                ? element.getEnclosingElement() + "#" + element
                : element.toString();
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
