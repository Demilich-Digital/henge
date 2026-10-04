package digital.demilich.henge.processor;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Processor;
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

    /** {@code outputDir} holds the compiled classes, usable as another compilation's classpath. */
    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path outputDir) {

        boolean hasErrorContaining(String snippet) {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .anyMatch(d -> d.getMessage(null).contains(snippet));
        }
    }

    static Result compile(JavaFileObject... sources) {
        return compileAgainst(null, sources);
    }

    /**
     * Compiles with {@code dependency}'s output on the classpath, so its types are seen as class
     * files rather than source -- the situation of an implementation module compiled against a
     * separately built contracts module.
     */
    static Result compileAgainst(Result dependency, JavaFileObject... sources) {
        return compile(dependency, List.of(), sources);
    }

    /** Runs {@code otherProcessors} alongside {@link ServiceVersionProcessor}, as another library's would be. */
    static Result compileWithProcessors(List<Processor> otherProcessors, JavaFileObject... sources) {
        return compile(null, otherProcessors, sources);
    }

    private static Result compile(Result dependency, List<Processor> otherProcessors, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

        Path outputDir;
        try {
            outputDir = Files.createTempDirectory("henge-processor-test");
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String classpath = System.getProperty("java.class.path");
        if (dependency != null) {
            classpath = dependency.outputDir() + File.pathSeparator + classpath;
        }
        List<String> options = List.of("-classpath", classpath);
        JavaCompiler.CompilationTask task =
                compiler.getTask(null, fileManager, diagnostics, options, null, List.of(sources));
        List<Processor> processors = new ArrayList<>(otherProcessors);
        processors.add(new ServiceVersionProcessor());
        task.setProcessors(processors);

        boolean success = task.call();
        return new Result(success, diagnostics.getDiagnostics(), outputDir);
    }
}
