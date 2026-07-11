package io.modular.processor;

import static org.assertj.core.api.Assertions.assertThat;

import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

class ServiceVersionProcessorTest {

    private static final String INTERFACE_SOURCE = """
            package fixture.%s;

            import io.modular.core.ModularService;
            import io.modular.core.AddedIn;

            @ModularService(name = "widget-service", defaultVersion = "1")
            public interface WidgetService {
                String basic();

                @AddedIn("2")
                java.util.List<String> advanced(int limit) throws java.io.IOException;
            }
            """;

    private static final String V1_SOURCE = """
            package fixture.%s;

            import io.modular.core.ServiceVersion;

            @ServiceVersion(value = WidgetService.class, version = "1")
            public class WidgetServiceV1 extends WidgetServiceSkeleton {
                @Override
                public String basic() {
                    return "v1";
                }
            }
            """;

    private static final String V2_CORRECT_SOURCE = """
            package fixture.%s;

            import io.modular.core.ServiceVersion;

            @ServiceVersion(value = WidgetService.class, version = "2")
            public class WidgetServiceV2 extends WidgetServiceSkeleton {
                @Override
                public String basic() {
                    return "v2";
                }

                @Override
                public java.util.List<String> advanced(int limit) throws java.io.IOException {
                    return java.util.List.of("a", "b");
                }
            }
            """;

    private static final String V2_BROKEN_SOURCE = """
            package fixture.%s;

            import io.modular.core.ServiceVersion;

            @ServiceVersion(value = WidgetService.class, version = "2")
            public class WidgetServiceV2 extends WidgetServiceSkeleton {
                @Override
                public String basic() {
                    return "v2";
                }
                // missing advanced() override -- this version is supposed to support it
            }
            """;

    @Test
    void versionInRangeOverridesCompileCleanly() {
        String pkg = "positive";
        TestCompiler.Result result = TestCompiler.compile(
                source(pkg, "WidgetService", INTERFACE_SOURCE),
                source(pkg, "WidgetServiceV1", V1_SOURCE),
                source(pkg, "WidgetServiceV2", V2_CORRECT_SOURCE));

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @Test
    void missingInRangeOverrideFailsCompilation() {
        String pkg = "negative";
        TestCompiler.Result result = TestCompiler.compile(
                source(pkg, "WidgetService", INTERFACE_SOURCE),
                source(pkg, "WidgetServiceV1", V1_SOURCE),
                source(pkg, "WidgetServiceV2", V2_BROKEN_SOURCE));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("WidgetServiceV2")).isTrue();
        assertThat(result.hasErrorContaining("advanced")).isTrue();
        assertThat(result.hasErrorContaining("version >= 2")).isTrue();
    }

    @Test
    void generatedSkeletonHandlesGenericsAndThrowsClause() {
        // The interface's @AddedIn method has a generic return type (List<String>) and a
        // checked-exception throws clause -- if the generated WidgetServiceSkeleton's stub
        // method weren't well-formed Java, this whole compilation would fail with a syntax or
        // type error rather than the specific diagnostics asserted in the other two tests.
        String pkg = "generics";
        TestCompiler.Result result = TestCompiler.compile(
                source(pkg, "WidgetService", INTERFACE_SOURCE),
                source(pkg, "WidgetServiceV1", V1_SOURCE),
                source(pkg, "WidgetServiceV2", V2_CORRECT_SOURCE));

        assertThat(result.success()).isTrue();
    }

    private static StringJavaFileObject source(String pkg, String simpleName, String template) {
        String qualifiedName = "fixture." + pkg + "." + simpleName;
        return new StringJavaFileObject(qualifiedName, template.formatted(pkg));
    }
}
