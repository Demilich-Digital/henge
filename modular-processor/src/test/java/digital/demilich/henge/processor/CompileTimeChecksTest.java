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
    void versionedMethodIsEnforcedOnAnImplementationCompiledSeparatelyFromItsInterface() {
        // Interface and skeleton in one compilation, implementations in a later one that sees them
        // only as class files -- the usual contracts-module / services-module split.
        var contracts = compile(src("xmod", "Svc", "@ModularService public interface Svc { String a(); @AddedIn(2) String b(); }"));
        assertThat(contracts.success()).isTrue();

        var v1 = TestCompiler.compileAgainst(contracts, src("xmodimpl", "V1",
                "@ServiceVersion(value = fixture.xmod.Svc.class, version = 1) "
                        + "public class V1 extends fixture.xmod.SvcSkeleton { public String a() { return \"a\"; } }"));
        assertThat(v1.success()).isTrue();

        var v2Missing = TestCompiler.compileAgainst(contracts, src("xmodimpl", "V2",
                "@ServiceVersion(value = fixture.xmod.Svc.class, version = 2) "
                        + "public class V2 extends fixture.xmod.SvcSkeleton { public String a() { return \"a\"; } }"));
        assertThat(v2Missing.success()).isFalse();
        assertThat(v2Missing.hasErrorContaining("does not implement 'b'")).isTrue();
    }

    @Test
    void redeclaringAnInheritedMethodIsNotAnOverload() {
        var result = compile(
                src("redecl", "Base", "public interface Base { String a(); }"),
                src("redecl", "Svc", "@ModularService public interface Svc extends Base { @Override String a(); }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void theSameMethodInheritedFromTwoSuperinterfacesIsNotAnOverload() {
        var plain = compile(
                src("diamond", "A", "public interface A { String a(); }"),
                src("diamond", "B", "public interface B { String a(); }"),
                src("diamond", "Svc", "@ModularService public interface Svc extends A, B { }"));
        assertThat(plain.success()).isTrue();

        // And a versioned one gets a single skeleton stub, not two clashing ones.
        var versioned = compile(
                src("diamondver", "A", "public interface A { String a(); @AddedIn(2) String b(); }"),
                src("diamondver", "B", "public interface B { @AddedIn(2) String b(); }"),
                src("diamondver", "Svc", "@ModularService public interface Svc extends A, B { }"),
                src("diamondver", "V1", "@ServiceVersion(value = Svc.class, version = 1) public class V1 extends SvcSkeleton { "
                        + "public String a() { return \"a\"; } }"));
        assertThat(versioned.success()).isTrue();
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
    void serviceNamesMustBeLowercaseKebabCase() {
        assertThat(compile(src("nameok", "Svc", "@ModularService(name = \"billing-v2\") public interface Svc { void a(); }")).success())
                .isTrue();
        assertThat(compile(src("namedefault", "BillingService", "@ModularService public interface BillingService { void a(); }")).success())
                .isTrue();

        for (String bad : new String[] {"billing/v2", "billing.v2", "Billing", "billing_v2", "billing--v2", "-billing"}) {
            var result = compile(src("namebad", "Svc", "@ModularService(name = \"" + bad + "\") public interface Svc { void a(); }"));
            assertThat(result.success()).as(bad).isFalse();
            assertThat(result.hasErrorContaining("lowercase kebab case")).as(bad).isTrue();
        }

        var badDefault = compile(src("namebaddefault", "Billing_Service", "@ModularService public interface Billing_Service { void a(); }"));
        assertThat(badDefault.success()).isFalse();
        assertThat(badDefault.hasErrorContaining("'billing_-service'")).isTrue();
    }

    @Test
    void serviceMethodNamesMustBeJavaIdentifiers() {
        var result = compile(src("methodbad", "Svc", "@ModularService public interface Svc { @ServiceMethod(name = \"a/b\") void a(); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("must be a Java identifier")).isTrue();
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
    void versionAnnotationsOnADefaultMethodAreRejectedOnce() {
        var result = compile(
                src("defver", "Svc", "@ModularService public interface Svc { String a(); @DeprecatedSince(3) default String b() { return \"d\"; } }"),
                src("defver", "V1", "@ServiceVersion(value = Svc.class, version = 1) public class V1 implements Svc { public String a() { return \"a\"; } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("is a default method with @AddedIn/@DeprecatedSince")).isTrue();
        // Not also reported as unimplemented: the interface's body is an implementation.
        assertThat(result.hasErrorContaining("does not implement")).isFalse();
    }

    @Test
    void nonPositiveVersionsAreRejected() {
        var result = compile(src("zero", "Svc", "@ModularService public interface Svc { String a(); @AddedIn(0) String b(); }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("must be a positive version")).isTrue();
    }

    // ---- what the annotations may be applied to ----

    @Test
    void nonPositiveServiceVersionsAndDefaultVersionsAreRejected() {
        var implVersion = compile(
                src("svzero", "Svc", "@ModularService public interface Svc { void a(); }"),
                src("svzero", "Impl", "@ServiceVersion(value = Svc.class, version = 0) public class Impl implements Svc { public void a() {} }"));
        assertThat(implVersion.success()).isFalse();
        assertThat(implVersion.hasErrorContaining("@ServiceVersion(version = 0)")).isTrue();

        var injectionSite = compile(
                src("svsite", "Svc", "@ModularService public interface Svc { void a(); }"),
                src("svsite", "Consumer", "public class Consumer { "
                        + "public Consumer(@ServiceVersion(value = Svc.class, version = -1) Svc svc) {} }"));
        assertThat(injectionSite.success()).isFalse();
        assertThat(injectionSite.hasErrorContaining("@ServiceVersion(version = -1)")).isTrue();

        var defaultVersion = compile(src("dvzero", "Svc", "@ModularService(defaultVersion = 0) public interface Svc { void a(); }"));
        assertThat(defaultVersion.success()).isFalse();
        assertThat(defaultVersion.hasErrorContaining("defaultVersion = 0")).isTrue();
    }

    @Test
    void injectionSiteServiceVersionMustNameTheInjectedInterface() {
        var svc = src("site", "Svc", "@ModularService public interface Svc { void a(); }");
        var other = src("site", "Other", "@ModularService public interface Other { void a(); }");

        var ok = compile(svc, src("site", "Consumer", "public class Consumer { "
                + "@ServiceVersion(value = Svc.class, version = 2) Svc field; "
                + "public Consumer(@ServiceVersion(value = Svc.class, version = 1) Svc a, "
                + "@ServiceVersion(value = Svc.class, version = 1) java.util.Optional<Svc> b, "
                + "@ServiceVersion(value = Svc.class, version = 1) java.util.function.Supplier<Svc> c) {} }"));
        assertThat(ok.success()).isTrue();

        var mismatch = compile(svc, other, src("site", "Consumer",
                "public class Consumer { public Consumer(@ServiceVersion(value = Other.class, version = 1) Svc svc) {} }"));
        assertThat(mismatch.success()).isFalse();
        assertThat(mismatch.hasErrorContaining("doesn't match its type")).isTrue();

        var notAService = compile(src("site", "Plain", "public interface Plain { void a(); }"), src("site", "Consumer",
                "public class Consumer { public Consumer(@ServiceVersion(value = Plain.class, version = 1) Plain p) {} }"));
        assertThat(notAService.success()).isFalse();
        assertThat(notAService.hasErrorContaining("not annotated @ModularService")).isTrue();
    }

    @Test
    void errorStatusMustBeA4xxOr5xxCode() {
        assertThat(compile(src("esok", "Missing", "@ErrorStatus(404) public class Missing extends RuntimeException {}")).success())
                .isTrue();

        for (int bad : new int[] {200, 302, 399, 600}) {
            var result = compile(src("esbad", "Bad", "@ErrorStatus(" + bad + ") public class Bad extends RuntimeException {}"));
            assertThat(result.success()).as(String.valueOf(bad)).isFalse();
            assertThat(result.hasErrorContaining("must be a 4xx or 5xx status code")).as(String.valueOf(bad)).isTrue();
        }
    }

    @Test
    void aPrivateServiceInterfaceIsRejectedClearly() {
        var direct = compile(src("privsvc", "Outer",
                "public class Outer { @ModularService private interface Svc { String a(); @AddedIn(2) String b(); } }"));
        assertThat(direct.success()).isFalse();
        assertThat(direct.hasErrorContaining("must be accessible from its package")).isTrue();
        assertThat(direct.hasErrorContaining("has private access")).isFalse();

        var enclosed = compile(src("privouter", "Outer",
                "public class Outer { private static class Inner { @ModularService interface Svc { String a(); } } }"));
        assertThat(enclosed.success()).isFalse();
        assertThat(enclosed.hasErrorContaining("must be accessible from its package")).isTrue();
    }

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
    void springStereotypesOnAnImplementationAreRejected() {
        // Stand-ins for Spring's own annotations, matched by name: this module has no Spring dependency.
        var component = new StringJavaFileObject("org.springframework.stereotype.Component",
                "package org.springframework.stereotype; public @interface Component {}");
        var service = new StringJavaFileObject("org.springframework.stereotype.Service",
                "package org.springframework.stereotype; @Component public @interface Service {}");
        var svc = src("stereo", "Svc", "@ModularService public interface Svc { void a(); }");

        var direct = compile(component, svc, src("stereo", "Impl",
                "@org.springframework.stereotype.Component @ServiceVersion(value = Svc.class, version = 1) "
                        + "public class Impl implements Svc { public void a() {} }"));
        assertThat(direct.success()).isFalse();
        assertThat(direct.hasErrorContaining("both @ServiceVersion and @Component")).isTrue();

        var meta = compile(component, service, svc, src("stereo", "Impl",
                "@org.springframework.stereotype.Service @ServiceVersion(value = Svc.class, version = 1) "
                        + "public class Impl implements Svc { public void a() {} }"));
        assertThat(meta.success()).isFalse();
        assertThat(meta.hasErrorContaining("both @ServiceVersion and @Service")).isTrue();
    }

    @Test
    void twoImplementationsOfTheSameVersionAreRejected() {
        var result = compile(
                src("dupver", "Svc", "@ModularService public interface Svc { void a(); }"),
                src("dupver", "I1", "@ServiceVersion(value = Svc.class, version = 1) public class I1 implements Svc { public void a() {} }"),
                src("dupver", "I2", "@ServiceVersion(value = Svc.class, version = 1) public class I2 implements Svc { public void a() {} }"),
                src("dupver", "I3", "@ServiceVersion(value = Svc.class, version = 2) public class I3 implements Svc { public void a() {} }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("Two implementations both claim version 1 of fixture.dupver.Svc")).isTrue();
        assertThat(result.hasErrorContaining("version 2")).isFalse();
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

    @Test
    void mapKeysMustBeReadableFromAJsonObjectKey() {
        var ok = compile(
                src("keyok", "Color", "public enum Color { RED }"),
                src("keyok", "Svc", "@ModularService public interface Svc { "
                        + "void put(ImmutableMap<String, Integer> a, ImmutableMap<java.util.UUID, String> b, "
                        + "ImmutableMap<Color, String> c, ImmutableMap<java.time.LocalDate, String> d, "
                        + "ImmutableMap<? extends Long, String> e); }"));
        assertThat(ok.success()).isTrue();

        var recordKey = compile(
                src("keyrec", "Point", "public record Point(int x, int y) {}"),
                src("keyrec", "Svc", "@ModularService public interface Svc { void put(ImmutableMap<Point, String> m); }"));
        assertThat(recordKey.success()).isFalse();
        assertThat(recordKey.hasErrorContaining("JSON object key")).isTrue();

        var optionalKey = compile(src("keyopt", "Svc",
                "@ModularService public interface Svc { ImmutableMap<java.util.Optional<String>, String> get(); }"));
        assertThat(optionalKey.success()).isFalse();
        assertThat(optionalKey.hasErrorContaining("JSON object key")).isTrue();

        var wildcardRecordKey = compile(
                src("keywild", "Point", "public record Point(int x, int y) {}"),
                src("keywild", "Svc", "@ModularService public interface Svc { void put(ImmutableMap<? extends Point, String> m); }"));
        assertThat(wildcardRecordKey.success()).isFalse();
        assertThat(wildcardRecordKey.hasErrorContaining("JSON object key")).isTrue();
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
