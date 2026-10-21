package jopt.io;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Superclass information needed to compute stack map frames, without loading any class. Looks in
 * the input classes, then the extra classpath, then the running JDK.
 */
public final class ClassHierarchy implements AutoCloseable {

    /** A class that is referenced but cannot be found; the class using it is left unoptimized. */
    public static final class TypeNotFoundException extends RuntimeException {
        public TypeNotFoundException(String type) {
            super(type);
        }
    }

    private record Info(String superName, boolean isInterface) {}

    private final Map<String, Info> known = new HashMap<>();
    private final List<ZipFile> classpath = new ArrayList<>();

    public ClassHierarchy(List<Path> classpathJars) {
        try {
            for (Path p : classpathJars) classpath.add(new ZipFile(p.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void add(byte[] classFile) {
        ClassReader cr = new ClassReader(classFile);
        known.put(cr.getClassName(), new Info(cr.getSuperName(), (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0));
    }

    /** The most specific common superclass of two internal names, as ASM's ClassWriter expects. */
    public String commonSuperClass(String a, String b) {
        if (a.equals(b)) return a;
        Info ia = info(a), ib = info(b);
        if (ia.isInterface() || ib.isInterface()) return "java/lang/Object";
        Set<String> ancestors = new HashSet<>();
        for (String c = a; c != null; c = info(c).superName()) ancestors.add(c);
        for (String c = b; c != null; c = info(c).superName()) if (ancestors.contains(c)) return c;
        return "java/lang/Object";
    }

    private Info info(String name) {
        Info i = known.get(name);
        if (i != null) return i;
        byte[] bytes = find(name + ".class");
        if (bytes == null) throw new TypeNotFoundException(name.replace('/', '.'));
        add(bytes);
        return known.get(name);
    }

    private byte[] find(String resource) {
        try {
            for (ZipFile z : classpath) {
                ZipEntry e = z.getEntry(resource);
                if (e != null) try (InputStream in = z.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }
            try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        for (ZipFile z : classpath) z.close();
    }
}
