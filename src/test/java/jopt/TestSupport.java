package jopt;

import jopt.io.ClassHierarchy;
import jopt.opt.Optimizer;
import jopt.opt.Report;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TestSupport {
    private TestSupport() {}

    static byte[] classBytes(Class<?> c) throws IOException {
        try (InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    static byte[] optimize(byte[] classFile, Report report) {
        try (ClassHierarchy h = new ClassHierarchy(List.of())) {
            h.add(classFile);
            return new Optimizer(Set.of(), h, report, true).optimizeClass(classFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Defines the given classes in a fresh loader whose parent is the platform loader. */
    static final class IsolatedLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        IsolatedLoader(Map<String, byte[]> classes) {
            super("isolated", ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = classes.get(name);
            if (b == null) throw new ClassNotFoundException(name);
            return defineClass(name, b, 0, b.length);
        }
    }
}
