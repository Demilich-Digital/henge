package digital.demilich.henge.processor;

import static org.assertj.core.api.Assertions.assertThat;

import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

class ServiceVersionProcessorTest {

    private static final String INTERFACE_SOURCE = """
            package fixture.%s;

            import digital.demilich.henge.core.ModularService;
            import digital.demilich.henge.core.AddedIn;

            @ModularService(name = "widget-service", defaultVersion = "1")
            public interface WidgetService {
                String basic();

                @AddedIn("2")
                digital.demilich.henge.core.ImmutableList<String> advanced(int limit) throws java.io.UncheckedIOException;
            }
            """;

    private static final String V1_SOURCE = """
            package fixture.%s;

            import digital.demilich.henge.core.ServiceVersion;

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

            import digital.demilich.henge.core.ServiceVersion;

            @ServiceVersion(value = WidgetService.class, version = "2")
            public class WidgetServiceV2 extends WidgetServiceSkeleton {
                @Override
                public String basic() {
                    return "v2";
                }

                @Override
                public digital.demilich.henge.core.ImmutableList<String> advanced(int limit) throws java.io.UncheckedIOException {
                    return digital.demilich.henge.core.ImmutableList.of("a", "b");
                }
            }
            """;

    private static final String V2_BROKEN_SOURCE = """
            package fixture.%s;

            import digital.demilich.henge.core.ServiceVersion;

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
        // throws clause -- if the generated WidgetServiceSkeleton's stub
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

    @Test
    void checkedExceptionOnModularServiceMethodFailsCompilation() {
        String pkg = "checkedexception";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "flaky-service", defaultVersion = "1")
                public interface FlakyService {
                    String read() throws java.io.IOException;
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "FlakyService", source));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("checked exception")).isTrue();
        assertThat(result.hasErrorContaining("java.io.IOException")).isTrue();
        assertThat(result.hasErrorContaining("read")).isTrue();
    }

    @Test
    void uncheckedExceptionOnModularServiceMethodCompilesCleanly() {
        String pkg = "uncheckedexception";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "flaky-service", defaultVersion = "1")
                public interface FlakyService {
                    String read() throws IllegalStateException;
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "FlakyService", source));

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @Test
    void mutablePojoParameterFailsCompilation() {
        String pkg = "mutablepojo";
        String pojoSource = """
                package fixture.%s;

                public class Widget {
                    public String name;
                }
                """.formatted(pkg);
        String serviceSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    void save(Widget widget);
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(
                source(pkg, "Widget", pojoSource),
                source(pkg, "WidgetService", serviceSource));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("not a valid @ModularService boundary type")).isTrue();
        assertThat(result.hasErrorContaining("Widget")).isTrue();
    }

    @Test
    void jpaEntityParameterFailsCompilationWithEntitySpecificMessage() {
        String pkg = "jpaentity";
        String entitySource = """
                package fixture.%s;

                @jakarta.persistence.Entity
                public class Widget {
                    public String name;
                }
                """.formatted(pkg);
        String serviceSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    void save(Widget widget);
                }
                """.formatted(pkg);
        String entityAnnotationStub = """
                package jakarta.persistence;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Entity {
                }
                """;

        TestCompiler.Result result = TestCompiler.compile(
                new StringJavaFileObject("jakarta.persistence.Entity", entityAnnotationStub),
                source(pkg, "Widget", entitySource),
                source(pkg, "WidgetService", serviceSource));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("it's a JPA entity")).isTrue();
    }

    @Test
    void guavaImmutableListAndMapParametersCompileCleanly() {
        // Guava isn't an actual dependency of this build -- these stubs stand in for it, proving
        // the processor recognizes com.google.common.collect.ImmutableList/ImmutableMap by FQN
        // alone (same mechanism as the @Entity check), with no Guava jar required.
        String guavaImmutableListStub = """
                package com.google.common.collect;

                public final class ImmutableList<E> extends java.util.AbstractList<E> {
                    public E get(int index) { throw new UnsupportedOperationException(); }
                    public int size() { return 0; }
                }
                """;
        String guavaImmutableMapStub = """
                package com.google.common.collect;

                public final class ImmutableMap<K, V> extends java.util.AbstractMap<K, V> {
                    public java.util.Set<Entry<K, V>> entrySet() { return java.util.Set.of(); }
                }
                """;

        String pkg = "guavacollections";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;
                import com.google.common.collect.ImmutableList;
                import com.google.common.collect.ImmutableMap;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    ImmutableList<String> names();
                    void save(ImmutableMap<String, Integer> counts);
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(
                new StringJavaFileObject("com.google.common.collect.ImmutableList", guavaImmutableListStub),
                new StringJavaFileObject("com.google.common.collect.ImmutableMap", guavaImmutableMapStub),
                source(pkg, "WidgetService", source));

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @Test
    void recordDtoParameterAndReturnTypeCompileCleanly() {
        String pkg = "recorddto";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ImmutableList;
                import digital.demilich.henge.core.ModularService;
                import java.util.Optional;
                import java.util.UUID;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    record Widget(UUID id, String name, ImmutableList<Tag> tags, Optional<String> note) {}
                    record Tag(String label) {}

                    Widget save(Widget widget);
                    ImmutableList<Widget> findAll();
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "WidgetService", source));

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    @Test
    void plainMutableListParameterFailsCompilation() {
        String pkg = "plainlist";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;
                import java.util.List;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    void save(List<String> names);
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "WidgetService", source));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("not a valid @ModularService boundary type")).isTrue();
        assertThat(result.hasErrorContaining("java.util.List")).isTrue();
    }

    @Test
    void arrayParameterFailsCompilation() {
        String pkg = "arrayparam";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    void save(String[] names);
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "WidgetService", source));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("arrays are mutable")).isTrue();
    }

    @Test
    void nonSemverServiceVersionFailsCompilationEvenWithoutAddedInOrDeprecatedSince() {
        // @ServiceVersion's value must always be a valid semantic version -- this interface never
        // uses @AddedIn/@DeprecatedSince at all, but the rule still applies unconditionally.
        String pkg = "nonsemverversion";
        String serviceSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    String basic();
                }
                """.formatted(pkg);
        String implSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ServiceVersion;

                @ServiceVersion(value = WidgetService.class, version = "north")
                public class WidgetServiceImpl implements WidgetService {
                    @Override
                    public String basic() {
                        return "v1";
                    }
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(
                source(pkg, "WidgetService", serviceSource),
                source(pkg, "WidgetServiceImpl", implSource));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("@ServiceVersion")).isTrue();
        assertThat(result.hasErrorContaining("'north'")).isTrue();
        assertThat(result.hasErrorContaining("could not be parsed as a semantic version")).isTrue();
    }

    @Test
    void dottedSemverServiceVersionOrdersCorrectlyAgainstAddedIn() {
        // "1.10" must sort after "1.9" the way semver orders it (numeric minor comparison), not
        // the way plain string/integer comparison would get wrong.
        String pkg = "dottedsemver";
        String serviceSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;
                import digital.demilich.henge.core.AddedIn;

                @ModularService(name = "widget-service", defaultVersion = "1.0")
                public interface WidgetService {
                    String basic();

                    @AddedIn("1.10")
                    String advanced();
                }
                """.formatted(pkg);
        String belowRangeSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ServiceVersion;

                @ServiceVersion(value = WidgetService.class, version = "1.9")
                public class WidgetServiceV19 extends WidgetServiceSkeleton {
                    @Override
                    public String basic() {
                        return "v1.9";
                    }
                    // correctly omits advanced() -- 1.9 < 1.10
                }
                """.formatted(pkg);
        String inRangeMissingOverrideSource = """
                package fixture.%s;

                import digital.demilich.henge.core.ServiceVersion;

                @ServiceVersion(value = WidgetService.class, version = "1.10")
                public class WidgetServiceV110 extends WidgetServiceSkeleton {
                    @Override
                    public String basic() {
                        return "v1.10";
                    }
                    // missing advanced() -- 1.10 >= 1.10, so this should fail to compile
                }
                """.formatted(pkg);

        TestCompiler.Result belowRangeResult = TestCompiler.compile(
                source(pkg, "WidgetService", serviceSource),
                source(pkg, "WidgetServiceV19", belowRangeSource));
        assertThat(belowRangeResult.success()).isTrue();
        assertThat(belowRangeResult.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);

        String pkg2 = "dottedsemverinrange";
        TestCompiler.Result inRangeResult = TestCompiler.compile(
                source(pkg2, "WidgetService", serviceSource.replace(pkg, pkg2)),
                source(pkg2, "WidgetServiceV110", inRangeMissingOverrideSource.replace(pkg, pkg2)));
        assertThat(inRangeResult.success()).isFalse();
        assertThat(inRangeResult.hasErrorContaining("advanced")).isTrue();
    }

    @Test
    void sealedInterfaceOfRecordsCompilesCleanly() {
        String pkg = "sealedrecord";
        String source = """
                package fixture.%s;

                import digital.demilich.henge.core.ModularService;

                @ModularService(name = "widget-service", defaultVersion = "1")
                public interface WidgetService {
                    sealed interface Shape permits Circle, Square {}
                    record Circle(double radius) implements Shape {}
                    record Square(double side) implements Shape {}

                    Shape describe(String name);
                }
                """.formatted(pkg);

        TestCompiler.Result result = TestCompiler.compile(source(pkg, "WidgetService", source));

        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }
}
