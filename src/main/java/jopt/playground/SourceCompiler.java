package jopt.playground;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles one Java source file in memory with the JDK's own {@code javac}.
 *
 * <p>Only compilation happens here: annotation processing is off and the class path is empty, so
 * user code can reference the JDK and itself but nothing from this tool, and no user code runs.
 */
public final class SourceCompiler {
    /** Class files are emitted for Java 21, the newest version the bundled ASM reads and the project targets. */
    public static final String RELEASE = "21";
    private static final String DEFAULT_FILE = "Playground";
    /** -Xmaxerrs caps errors only; warnings from -Xlint:all are capped here. */
    static final int MAX_DIAGNOSTICS = 100;
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "(?m)^\\s*public\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp|static)\\s+)*"
                    + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private SourceCompiler() {}

    public record Message(String kind, long line, long column, String text) {}

    /** {@code classes} maps internal names ({@code a/b/C}) to class files; empty when compilation failed. */
    public record Result(boolean success, String fileName, Map<String, byte[]> classes, List<Message> diagnostics) {}

    public static final class CompilerUnavailableException extends RuntimeException {
        CompilerUnavailableException() {
            super("no Java compiler available: run the playground on a JDK, not a JRE");
        }
    }

    public static Result compile(String source) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new CompilerUnavailableException();
        String fileName = fileNameFor(source);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        try (StandardJavaFileManager std = javac.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            std.setLocation(StandardLocation.CLASS_PATH, List.of());
            MemoryFileManager files = new MemoryFileManager(std, outputs);
            List<String> options = List.of("--release", RELEASE, "-proc:none", "-g:source,lines", "-implicit:none",
                    "-Xlint:all", "-Xmaxerrs", "50");
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics, options, null,
                    List.of(new SourceFile(fileName, source)));
            boolean ok = task.call();
            List<Message> messages = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics())
                if (messages.size() < MAX_DIAGNOSTICS) messages.add(new Message(kind(d.getKind()), d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT)));
            Map<String, byte[]> classes = new TreeMap<>();
            if (ok) outputs.forEach((name, bytes) -> classes.put(name, bytes.toByteArray()));
            return new Result(ok, fileName + ".java", classes, messages);
        } catch (IOException e) {
            throw new IllegalStateException("closing the compiler's file manager failed", e);
        }
    }

    /** javac requires a public top-level type to live in a file of the same name. */
    static String fileNameFor(String source) {
        Matcher m = PUBLIC_TYPE.matcher(source);
        return m.find() ? m.group(1) : DEFAULT_FILE;
    }

    private static String kind(Diagnostic.Kind k) {
        return switch (k) {
            case ERROR -> "error";
            case WARNING, MANDATORY_WARNING -> "warning";
            default -> "note";
        };
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String name, String source) {
            super(URI.create("string:///" + name + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /** Keeps compiler output in memory instead of writing class files to disk. */
    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;

        MemoryFileManager(StandardJavaFileManager fm, Map<String, ByteArrayOutputStream> outputs) {
            super(fm);
            this.outputs = outputs;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            String internal = className.replace('.', '/');
            return new SimpleJavaFileObject(URI.create("mem:///" + internal + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    outputs.put(internal, out);
                    return out;
                }
            };
        }
    }
}
