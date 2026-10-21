package jopt.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Reads and writes a set of files from/to a jar, a directory tree, or a single .class file. */
public final class ClassFiles {
    private ClassFiles() {}

    /** A file inside the input, addressed by its '/'-separated relative path. */
    public record Entry(String path, byte[] data) {
        public boolean isClass() {
            return path.endsWith(".class") && !path.endsWith("module-info.class");
        }
    }

    public static List<Entry> read(Path input) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try (Stream<Path> files = Files.walk(input)) {
                for (Path p : files.filter(Files::isRegularFile).sorted().toList())
                    out.add(new Entry(input.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p)));
            }
        } else if (isArchive(input)) {
            try (ZipFile z = new ZipFile(input.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    try (InputStream in = z.getInputStream(e)) {
                        out.add(new Entry(e.getName(), in.readAllBytes()));
                    }
                }
            }
        } else {
            out.add(new Entry(input.getFileName().toString(), Files.readAllBytes(input)));
        }
        return out;
    }

    public static void write(Path output, List<Entry> entries) throws IOException {
        if (isArchive(output)) {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            try (OutputStream os = Files.newOutputStream(output); ZipOutputStream zip = new ZipOutputStream(os)) {
                for (Entry e : entries) {
                    zip.putNextEntry(new ZipEntry(e.path()));
                    zip.write(e.data());
                    zip.closeEntry();
                }
            }
        } else if (entries.size() == 1 && output.toString().endsWith(".class")) {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.write(output, entries.get(0).data());
        } else {
            for (Entry e : entries) {
                Path p = output.resolve(e.path());
                Files.createDirectories(p.getParent());
                Files.write(p, e.data());
            }
        }
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jar") || n.endsWith(".zip");
    }

    /** True for jar signature files, which become invalid once any class changes. */
    public static boolean isSignatureFile(String path) {
        return path.matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    }
}
