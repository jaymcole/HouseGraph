package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers concrete {@link BaseNode} subclasses on the classpath under
 * {@value #BASE_PACKAGE}, so the UI can build an "Add Node" menu straight from the
 * package/folder structure instead of maintaining a hardcoded list. Dropping a new
 * node class anywhere under that package — in whatever subpackage/folder makes sense
 * — is enough for it to show up; nothing else needs to be registered.
 * <p>
 * A class annotated {@code @Node.Disabled} is skipped by {@link #discover()} (and so
 * never appears in the "Add Node" menu), but is still resolvable via
 * {@link #resolveClass} — a graph saved while the node type was enabled can still be
 * loaded even after it's since been disabled.
 */
public final class NodeRegistry {

    public static final String BASE_PACKAGE = "io.github.jaymcole.housegraph.graph.nodes";

    /**
     * One discovered node type.
     *
     * @param nodeClass    the concrete BaseNode subclass
     * @param categoryPath its subpackage path relative to {@link #BASE_PACKAGE}, dot-separated,
     *                     empty if the class sits directly in the base package
     * @param displayName  {@code @Display.Name} if present, else the simple class name
     */
    public record Entry(Class<? extends BaseNode> nodeClass, String categoryPath, String displayName) {
    }

    private NodeRegistry() {
    }

    public static List<Entry> discover() {
        List<Entry> entries = new ArrayList<>();
        String basePath = BASE_PACKAGE.replace('.', '/');
        try {
            Enumeration<URL> roots = Thread.currentThread().getContextClassLoader().getResources(basePath);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    scanDirectory(new File(root.toURI()), BASE_PACKAGE, entries);
                } else if ("jar".equals(root.getProtocol())) {
                    scanJar(root, basePath, entries);
                }
            }
        } catch (IOException | URISyntaxException e) {
            System.err.println("Failed to scan for node classes under " + BASE_PACKAGE + ": " + e);
        }
        entries.sort(Comparator.comparing(Entry::categoryPath).thenComparing(entry -> entry.nodeClass().getSimpleName()));
        return entries;
    }

    /** Resolves a fully-qualified class name (e.g. from a save file) back to a loadable node class, or null if it's not a valid one. */
    public static Class<? extends BaseNode> resolveClass(String className) {
        try {
            Class<?> type = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            if (BaseNode.class.isAssignableFrom(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                @SuppressWarnings("unchecked")
                Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) type;
                return nodeClass;
            }
        } catch (ClassNotFoundException e) {
            // fall through to null
        }
        return null;
    }

    /** Creates a fresh instance of a discovered node class via its no-arg constructor, or null if that fails. */
    public static BaseNode instantiate(Class<? extends BaseNode> nodeClass) {
        try {
            return nodeClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            System.err.println("Failed to instantiate node " + nodeClass.getName() + ": " + e);
            return null;
        }
    }

    /**
     * Creates a fresh instance of the same node type as {@code source}, with its
     * current input/output values copied over by position. Used for copy/paste: no
     * per-node-type clone() is needed since {@code configureInputs()}/{@code configureOutputs()}
     * always build the same list shape for a given class.
     */
    public static BaseNode duplicate(BaseNode source) {
        BaseNode copy = instantiate(source.getClass());
        if (copy != null) {
            copyValues(source.getInputs(), copy.getInputs());
            copyValues(source.getOutputs(), copy.getOutputs());
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void copyValues(List<NodeVariable> from, List<NodeVariable> to) {
        for (int i = 0; i < Math.min(from.size(), to.size()); i++) {
            to.get(i).setValue(from.get(i).getValue());
        }
    }

    private static void scanDirectory(File directory, String packageName, List<Entry> out) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), out);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
                String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
                tryAdd(packageName + "." + simpleName, out);
            }
        }
    }

    private static void scanJar(URL jarUrl, String basePath, List<Entry> out) throws IOException {
        JarURLConnection connection = (JarURLConnection) jarUrl.openConnection();
        try (JarFile jarFile = connection.getJarFile()) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                String name = jarEntries.nextElement().getName();
                if (!name.startsWith(basePath + "/") || !name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                tryAdd(name.substring(0, name.length() - ".class".length()).replace('/', '.'), out);
            }
        }
    }

    private static void tryAdd(String className, List<Entry> out) {
        try {
            Class<?> type = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            if (BaseNode.class.isAssignableFrom(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())
                    && !type.isAnnotationPresent(Node.Disabled.class)) {
                @SuppressWarnings("unchecked")
                Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) type;
                out.add(new Entry(nodeClass, categoryOf(nodeClass), displayNameOf(nodeClass)));
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            System.err.println("Skipping unloadable class " + className + ": " + e);
        }
    }

    private static String categoryOf(Class<? extends BaseNode> nodeClass) {
        String pkg = nodeClass.getPackageName();
        if (pkg.equals(BASE_PACKAGE)) {
            return "";
        }
        return pkg.startsWith(BASE_PACKAGE + ".") ? pkg.substring(BASE_PACKAGE.length() + 1) : pkg;
    }

    private static String displayNameOf(Class<? extends BaseNode> nodeClass) {
        Display.Name displayName = nodeClass.getAnnotation(Display.Name.class);
        if (displayName != null && !displayName.value().isBlank()) {
            return displayName.value();
        }
        return nodeClass.getSimpleName();
    }
}
