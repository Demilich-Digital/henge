package digital.demilich.henge.processor;

import static org.assertj.core.api.Assertions.assertThat;

import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Spring's {@code @Scheduled} runs once per node, so it is an error without
 * {@code @HengeAcknowledgeThisRunsOnEveryNode}. Spring is stubbed, as it is for the stereotype checks: the
 * processor matches its annotations by name.
 */
class PlainScheduledCheckTest {

    private static final JavaFileObject SCHEDULED = new StringJavaFileObject("org.springframework.scheduling.annotation.Scheduled",
            "package org.springframework.scheduling.annotation;"
                    + "@java.lang.annotation.Repeatable(Schedules.class) "
                    + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                    + "public @interface Scheduled { String cron() default \"\"; long fixedRate() default -1; }");
    private static final JavaFileObject SCHEDULES = new StringJavaFileObject("org.springframework.scheduling.annotation.Schedules",
            "package org.springframework.scheduling.annotation;"
                    + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                    + "public @interface Schedules { Scheduled[] value(); }");

    private static JavaFileObject job(String pkg, String body) {
        return new StringJavaFileObject("fixture." + pkg + ".Job",
                "package fixture." + pkg + ";\n"
                        + "import digital.demilich.henge.core.*;\n"
                        + "import org.springframework.scheduling.annotation.*;\n" + body);
    }

    private static TestCompiler.Result compile(JavaFileObject job) {
        return TestCompiler.compile(SCHEDULED, SCHEDULES, job);
    }

    @Test
    void aPlainScheduledMethodIsAnErrorNamingBothWaysOut() {
        var result = compile(job("plain", "public class Job { @Scheduled(fixedRate = 1000) public void evict() { } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("@Scheduled method fixture.plain.Job#evict would run on every node")).isTrue();
        assertThat(result.hasErrorContaining("@HengeScheduled")).isTrue();
        assertThat(result.hasErrorContaining("@HengeAcknowledgeThisRunsOnEveryNode")).isTrue();
    }

    @Test
    void severalScheduledAnnotationsOnOneMethodAreOneError() {
        var result = compile(job("repeated",
                "public class Job { @Scheduled(fixedRate = 1000) @Scheduled(cron = \"0 0 * * * *\") public void evict() { } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)).hasSize(1);
        assertThat(result.hasErrorContaining("fixture.repeated.Job#evict")).isTrue();
    }

    @Test
    void anAcknowledgedMethodCompiles() {
        var result = compile(job("method",
                "public class Job { @Scheduled(fixedRate = 1000) @HengeAcknowledgeThisRunsOnEveryNode public void evict() { } }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void anAcknowledgedClassCoversItsMethods() {
        var result = compile(job("type",
                "@HengeAcknowledgeThisRunsOnEveryNode public class Job { "
                        + "@Scheduled(fixedRate = 1000) public void evict() { } "
                        + "@Scheduled(fixedRate = 2000) public void flush() { } }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void aClassNestedInAnAcknowledgedOneIsCovered() {
        var result = compile(job("nested",
                "@HengeAcknowledgeThisRunsOnEveryNode public class Job { "
                        + "public static class Inner { @Scheduled(fixedRate = 1000) public void evict() { } } }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void anAcknowledgementOnAnotherMethodDoesNotCoverThisOne() {
        var result = compile(job("other",
                "public class Job { "
                        + "@Scheduled(fixedRate = 1000) @HengeAcknowledgeThisRunsOnEveryNode public void evict() { } "
                        + "@Scheduled(fixedRate = 2000) public void flush() { } }"));

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrorContaining("Job#flush")).isTrue();
        assertThat(result.hasErrorContaining("Job#evict")).isFalse();
    }

    @Test
    void aHengeScheduledMethodIsNotAffected() {
        var result = TestCompiler.compile(SCHEDULED, SCHEDULES, job("henge",
                "public class Job { @HengeScheduled(cron = \"0 0 * * * *\") public void run() { } }"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void aModuleWithoutSpringOnItsClasspathIsUnaffected() {
        var result = TestCompiler.compile(new StringJavaFileObject("fixture.nospring.Plain",
                "package fixture.nospring; public class Plain { public void run() { } }"));

        assertThat(result.success()).isTrue();
    }
}
