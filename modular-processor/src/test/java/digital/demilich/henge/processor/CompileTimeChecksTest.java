package digital.demilich.henge.processor;

import static org.assertj.core.api.Assertions.assertThat;

import javax.lang.model.SourceVersion;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Compile-time checks that apply across the whole shape of a {@code @ModularService} interface and
 * its implementations: inherited methods, overloads, static methods, version ranges, and what
 * {@code @ModularService} / {@code @ServiceVersion} may be applied to.
 */
class CompileTimeChecksTest {

    private static final String HEADER = "package fixture.%s;\nimport digital.demilich.henge.core.*;\n";

    private static JavaFileObject src(String pkg, String name, String body) {
        return new StringJavaFileObject("fixture." + pkg + "." + name, (HEADER + body).formatted(pkg));
    }

    private static TestCompiler.Result compile(JavaFileObject... sources) {
        return TestCompiler.compile(sources);
    }

    // ---- inherited methods are validated like declared ones ----

    @Test
    void inheritedMutableParameterIsRejected() {
        var result = compile(
                src("inhmut", "Base", "public interface Base { void save(java.util.List<String> names); }"),
                src("inhmut", "Svc", "@ModularService public interface Svc extends Base { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("not a valid @ModularService boundary type")).isTrue();
    }

    @Test
    void inheritedCheckedExceptionIsRejected() {
        var result = compile(
                src("inhchk", "Base", "public interface Base { void run() throws Exception; }"),
                src("inhchk", "Svc", "@ModularService public interface Svc extends Base { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("declares checked exception")).isTrue();
    }

    @Test
    void inheritedVersionedMethodGetsASkeletonStubAndIsEnforcedOnImplementations() {
        String base = "public interface Base { String a(); @AddedIn(2) String b(); }";
        String svc = "@ModularService public interface Svc extends Base { }";
        var v1 = src("inhver", "V1",
                "@ServiceVersion(value = Svc.class, version = 1) public class V1 extends SvcSkeleton { public String a() { return \"a\"; } }");
        var v2Missing = src("inhver", "V2",
                "@ServiceVersion(value = Svc.class, version = 2) public class V2 extends SvcSkeleton { public String a() { return \"a\"; } }");

        assertThat(compile(src("inhver", "Base", base), src("inhver", "Svc", svc), v1).success()).isTrue();

        var broken = compile(src("inhver", "Base", base), src("inhver", "Svc", svc), v1, v2Missing);
        assertThat(broken.success()).isFalse();
        assertThat(broken.hasErrorContaining("does not implement 'b'")).isTrue();
    }

    @Test
    void redeclaringAnInheritedMethodIsNotAnOverload() {
        var result = compile(
                src("redecl", "Base", "public interface Base { String a(); }"),
                src("redecl", "Svc", "@ModularService public interface Svc extends Base { @Override String a(); }"));

        assertThat(result.success()).isTrue();
    }

    // ---- method shapes ----

    @Test
    void overloadedMethodsAreRejected() {
        var result = compile(src("overload", "Svc", "@ModularService public interface Svc { void a(String s); void a(int i); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("both resolve to the RPC name 'a'")).isTrue();
    }

    @Test
    void serviceMethodNameDisambiguatesOverloads() {
        var result = compile(src("overloadok", "Svc",
                "@ModularService public interface Svc { void a(String s); @ServiceMethod(name = \"aWithInt\") void a(int i); }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void staticMethodIsRejectedButDefaultAndPrivateAreFine() {
        var rejected = compile(src("static1", "Svc", "@ModularService public interface Svc { static String s() { return \"\"; } String a(); }"));
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.hasErrorContaining("is static")).isTrue();

        var fine = compile(src("static2", "Svc",
                "@ModularService public interface Svc { String a(); default String b() { return helper(); } private String helper() { return \"\"; } }"));
        assertThat(fine.success()).isTrue();
    }

    // ---- version ranges ----

    @Test
    void emptyVersionRangeIsRejected() {
        var result = compile(src("range", "Svc",
                "@ModularService public interface Svc { String a(); @AddedIn(3) @DeprecatedSince(2) String b(); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("the version range is empty")).isTrue();
    }

    @Test
    void nonPositiveVersionsAreRejected() {
        var result = compile(src("zero", "Svc", "@ModularService public interface Svc { String a(); @AddedIn(0) String b(); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("must be a positive version")).isTrue();
    }

    // ---- what the annotations may be applied to ----

    @Test
    void modularServiceOnAClassIsRejected() {
        var result = compile(src("cls", "Svc", "@ModularService public class Svc { public String a() { return null; } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("@ModularService can only be applied to an interface")).isTrue();
    }

    @Test
    void serviceVersionImplementationMustImplementItsInterface() {
        var result = compile(
                src("noimpl", "Svc", "@ModularService public interface Svc { String a(); }"),
                src("noimpl", "Impl", "@ServiceVersion(value = Svc.class, version = 1) public class Impl { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("does not implement")).isTrue();
    }

    @Test
    void serviceVersionInterfaceMustBeAModularService() {
        var result = compile(
                src("plain", "Svc", "public interface Svc { String a(); }"),
                src("plain", "Impl", "@ServiceVersion(value = Svc.class, version = 1) public class Impl implements Svc { public String a() { return null; } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("is not annotated @ModularService")).isTrue();
    }

    @Test
    void serviceVersionOnAnAbstractClassIsRejected() {
        var result = compile(
                src("abs", "Svc", "@ModularService public interface Svc { String a(); }"),
                src("abs", "Impl", "@ServiceVersion(value = Svc.class, version = 1) public abstract class Impl implements Svc { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("it is abstract")).isTrue();
    }

    @Test
    void serviceVersionOnANonStaticInnerClassIsRejected() {
        var result = compile(
                src("inner", "Svc", "@ModularService public interface Svc { String a(); }"),
                src("inner", "Outer", "public class Outer { @ServiceVersion(value = Svc.class, version = 1) public class Impl implements Svc { public String a() { return null; } } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("non-static inner class")).isTrue();
    }

    @Test
    void recordImplementationsAreAccepted() {
        String svc = "@ModularService(defaultVersion = 2) public interface Svc { String a(); @AddedIn(2) String b(); }";
        var ok = compile(src("rec", "Svc", svc), src("rec", "Impl",
                "@ServiceVersion(value = Svc.class, version = 2) public record Impl() implements Svc { public String a() { return null; } public String b() { return null; } }"));

        assertThat(ok.success()).isTrue();
    }

    // ---- what counts as "implemented" ----

    private static final String VERSIONED_SVC =
            "@ModularService(defaultVersion = 2) public interface Svc { String a(); @AddedIn(2) String b(); }";

    @Test
    void versionedMethodInheritedFromABaseClassCountsAsImplemented() {
        var result = compile(
                src("basecls", "Svc", VERSIONED_SVC),
                src("basecls", "BaseImpl",
                        "public abstract class BaseImpl extends SvcSkeleton { public String a() { return \"a\"; } public String b() { return \"b\"; } }"),
                src("basecls", "Impl", "@ServiceVersion(value = Svc.class, version = 2) public class Impl extends BaseImpl { }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void laterVersionMayInheritAnEarlierVersionsImplementation() {
        var result = compile(
                src("chain", "Svc", VERSIONED_SVC),
                src("chain", "V1",
                        "@ServiceVersion(value = Svc.class, version = 1) public class V1 extends SvcSkeleton { public String a() { return \"a\"; } }"),
                src("chain", "V2", "@ServiceVersion(value = Svc.class, version = 2) public class V2 extends V1 { public String b() { return \"b\"; } }"),
                src("chain", "V3", "@ServiceVersion(value = Svc.class, version = 3) public class V3 extends V2 { }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void methodLeftToTheSkeletonStubIsStillReportedEvenThroughABaseClass() {
        var result = compile(
                src("stub", "Svc", VERSIONED_SVC),
                src("stub", "BaseImpl", "public abstract class BaseImpl extends SvcSkeleton { public String a() { return \"a\"; } }"),
                src("stub", "Impl", "@ServiceVersion(value = Svc.class, version = 2) public class Impl extends BaseImpl { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("does not implement 'b'")).isTrue();
    }

    // ---- generics ----

    @Test
    void genericMethodIsRejectedWithoutBrokenGeneratedCode() {
        var result = compile(src("genmeth", "Svc",
                "@ModularService public interface Svc { String a(); @AddedIn(2) <T> T echo(T value); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("declares its own type parameters")).isTrue();
        assertThat(result.hasErrorContaining("cannot find symbol")).as("no confusing errors from generated code").isFalse();
    }

    @Test
    void genericInterfaceIsRejected() {
        var result = compile(src("genif", "Svc", "@ModularService public interface Svc<T> { T get(); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("can't be generic")).isTrue();
    }

    @Test
    void methodInheritedFromAGenericInterfaceIsRejected() {
        var result = compile(
                src("geninh", "Base", "public interface Base<T> { T get(); }"),
                src("geninh", "Svc", "@ModularService public interface Svc extends Base<String> { }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("is inherited from the generic interface")).isTrue();
    }

    @Test
    void genericRecordWithAnAllowedTypeArgumentIsAccepted() {
        var result = compile(
                src("recok", "Point", "public record Point(int x, int y) {}"),
                src("recok", "Box", "public record Box<T>(T value, ImmutableList<T> more) {}"),
                src("recok", "Svc", "@ModularService public interface Svc { Box<Point> put(Box<String> b); }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void genericRecordTypeArgumentIsValidated() {
        var result = compile(
                src("recbad", "Box", "public record Box<T>(T value) {}"),
                src("recbad", "Svc", "@ModularService public interface Svc { void put(Box<java.util.List<String>> b); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("java.util.List<java.lang.String>")).isTrue();
    }

    @Test
    void nestingTheSameGenericRecordDoesNotHideAMutableTypeArgument() {
        var result = compile(
                src("recnest", "Box", "public record Box<T>(T value) {}"),
                src("recnest", "Svc", "@ModularService public interface Svc { void put(Box<Box<java.util.List<String>>> b); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("java.util.List<java.lang.String>")).isTrue();
    }

    @Test
    void rawGenericRecordIsRejected() {
        var result = compile(
                src("recraw", "Box", "public record Box<T>(T value) {}"),
                src("recraw", "Svc", "@ModularService public interface Svc { @SuppressWarnings(\"rawtypes\") void put(Box b); }"));

        assertThat(result.success()).isFalse();
    }

    @Test
    void recursiveGenericRecordIsAccepted() {
        var result = compile(
                src("recrec", "Node", "public record Node<T>(T value, ImmutableList<Node<T>> children) {}"),
                src("recrec", "Svc", "@ModularService public interface Svc { Node<String> tree(); }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void wildcardWithAnAllowedUpperBoundIsAccepted() {
        var result = compile(
                src("wildok", "Point", "public record Point(int x) {}"),
                src("wildok", "Svc", "@ModularService public interface Svc { void put(ImmutableList<? extends Point> p); }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void wildcardUpperBoundIsValidated() {
        var result = compile(src("wildbad", "Svc",
                "@ModularService public interface Svc { void put(ImmutableList<? extends java.util.List<String>> l); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("not a valid @ModularService boundary type")).isTrue();
    }

    @Test
    void unboundedAndLowerBoundedWildcardsAreRejected() {
        var unbounded = compile(src("wildun", "Svc", "@ModularService public interface Svc { void put(ImmutableList<?> l); }"));
        assertThat(unbounded.success()).isFalse();
        assertThat(unbounded.hasErrorContaining("no usable upper bound")).isTrue();

        var lower = compile(src("wildlow", "Svc", "@ModularService public interface Svc { void put(ImmutableList<? super String> l); }"));
        assertThat(lower.success()).isFalse();
        assertThat(lower.hasErrorContaining("no usable upper bound")).isTrue();
    }

    // ---- generated skeletons ----

    @Test
    void twoInterfacesCollidingOnASkeletonNameAreRejected() {
        var result = compile(
                src("collide", "A", "public class A { @ModularService(name = \"x\") public interface Svc { String a(); @AddedIn(2) String b(); } }"),
                src("collide", "B", "public class B { @ModularService(name = \"y\") public interface Svc { String a(); @AddedIn(2) String b(); } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("distinct simple names")).isTrue();
    }

    @Test
    void processorSupportsWhateverSourceVersionTheCompilerIsRunning() {
        assertThat(new ServiceVersionProcessor().getSupportedSourceVersion()).isEqualTo(SourceVersion.latestSupported());
    }
}
