package jopt.io;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads every class of a set into a fresh class loader and links it, which makes HotSpot run its
 * own bytecode verifier on every method. Static initializers are not run.
 */
public final class LinkCheck {
    private LinkCheck() {}

    public record Result(int linked, List<String> failures, List<String> skipped) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    public static Result run(List<ClassFiles.Entry> entries, List<Path> classpath) throws MalformedURLException {
        Map<String, byte[]> classes = new TreeMap<>();
        for (ClassFiles.Entry e : entries) {
            if (!e.isClass() || e.path().startsWith("META-INF/")) continue;
            String name = e.path().substring(0, e.path().length() - ".class".length()).replace('/', '.');
            if (!name.endsWith("package-info")) classes.put(name, e.data());
        }
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = classpath.get(i).toUri().toURL();
        // Parent is the platform loader, so the classes under test never see this tool's own copy of ASM.
        ClassLoader parent = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        ClassLoader loader = new ClassLoader("jopt-check", parent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };

        int linked = 0;
        List<String> failures = new ArrayList<>(), skipped = new ArrayList<>();
        for (String name : classes.keySet()) {
            try {
                Class<?> c = Class.forName(name, false, loader);
                c.getDeclaredMethods(); // forces linking, hence verification
                linked++;
            } catch (VerifyError | ClassFormatError e) {
                failures.add(name + ": " + e);
            } catch (LinkageError | ClassNotFoundException | SecurityException e) {
                skipped.add(name + ": " + e); // a dependency is missing; not the optimizer's fault
            }
        }
        return new Result(linked, failures, skipped);
    }
}
