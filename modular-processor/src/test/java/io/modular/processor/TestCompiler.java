package io.modular.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Runs {@link ServiceVersionProcessor} against in-memory fixture sources using the real
 * {@code javax.tools.JavaCompiler} directly, in-process — no external compile-testing library.
 */
final class TestCompiler {

    private TestCompiler() {
    }

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        boolean hasErrorContaining(String snippet) {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .anyMatch(d -> d.getMessage(null).contains(snippet));
        }
    }

    static Result compile(JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

        try {
            Path outputDir = Files.createTempDirectory("modular-processor-test");
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<String> options = List.of("-classpath", System.getProperty("java.class.path"));
        JavaCompiler.CompilationTask task =
                compiler.getTask(null, fileManager, diagnostics, options, null, List.of(sources));
        task.setProcessors(List.of(new ServiceVersionProcessor()));

        boolean success = task.call();
        return new Result(success, diagnostics.getDiagnostics());
    }
}
