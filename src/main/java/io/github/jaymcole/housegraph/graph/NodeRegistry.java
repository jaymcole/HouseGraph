package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>
 * <b>Save-file identity.</b> A node is written to a save file by a stable <em>type id</em>
 * ({@link #persistentTypeId}), not its fully-qualified class name. The id defaults to the simple
 * class name — which already survives moving the class between packages/category folders — and a
 * class can pin a different id (or extra {@link io.github.jaymcole.housegraph.annotations.Node.Type
 * aliases}) with {@code @Node.Type} to stay resolvable across a rename. {@link #resolveClass} maps a
 * saved id back to its class via an index of every node type's ids, falling back to
 * fully-qualified-class-name resolution for saves written before this scheme (or by an
 * un-indexed type).
 */
public final class NodeRegistry {

    private static final Logger log = Log.get(NodeRegistry.class);

    public static final String BASE_PACKAGE = "io.github.jaymcole.housegraph.graph.nodes";

    /** Lazily-built, cached index from every node type's stable id(s) to its class (see {@link #resolveClass}). */
    private static volatile Map<String, Class<? extends BaseNode>> idIndex;

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
        for (Class<? extends BaseNode> nodeClass : scanAllNodeClasses()) {
            // Disabled types stay loadable (and so stay in the id index), but are kept out of the menu.
            if (nodeClass.isAnnotationPresent(Node.Disabled.class)) {
                continue;
            }
            entries.add(new Entry(nodeClass, categoryOf(nodeClass), displayNameOf(nodeClass)));
        }
        entries.sort(Comparator.comparing(Entry::categoryPath).thenComparing(entry -> entry.nodeClass().getSimpleName()));
        return entries;
    }

    /**
     * Resolves a saved node {@code type} back to a loadable node class, or null if none matches.
     * Tries the {@link #idIndex() stable-id index} first — matching a {@code @Node.Type} id, one of
     * its aliases, or a simple class name — then falls back to fully-qualified-class-name resolution,
     * so a save written before type ids existed (which stored the class name) still opens.
     *
     * @param type the saved type id or class name
     * @return the loadable node class, or null if it can't be resolved
     */
    public static Class<? extends BaseNode> resolveClass(String type) {
        if (type == null) {
            return null;
        }
        Class<? extends BaseNode> byId = idIndex().get(type);
        if (byId != null) {
            return byId;
        }
        // Fallback: a fully-qualified class name from a pre-type-id save (or an unindexed type).
        try {
            Class<?> loaded = Class.forName(type, false, Thread.currentThread().getContextClassLoader());
            if (BaseNode.class.isAssignableFrom(loaded) && !loaded.isInterface() && !Modifier.isAbstract(loaded.getModifiers())) {
                @SuppressWarnings("unchecked")
                Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) loaded;
                return nodeClass;
            }
        } catch (ClassNotFoundException e) {
            // fall through to null
        }
        return null;
    }

    /**
     * The stable id a node type is written under in save files: its {@code @Node.Type} value if it
     * declares one, otherwise its simple class name. The simple name already survives moving the
     * class between packages; {@code @Node.Type} is for surviving a class rename. See {@link Node.Type}.
     *
     * @param nodeClass the node type
     * @return its persistent type id
     */
    public static String persistentTypeId(Class<? extends BaseNode> nodeClass) {
        Node.Type type = nodeClass.getAnnotation(Node.Type.class);
        if (type != null && !type.value().isBlank()) {
            return type.value();
        }
        return nodeClass.getSimpleName();
    }

    /**
     * The index from every node type's ids to its class, built once and cached. Each type contributes
     * its simple class name plus any {@code @Node.Type} value/aliases; an id claimed by two types is
     * dropped as ambiguous (so it falls back to class-name resolution) rather than resolving to the
     * wrong one.
     */
    private static Map<String, Class<? extends BaseNode>> idIndex() {
        Map<String, Class<? extends BaseNode>> index = idIndex;
        if (index == null) {
            index = buildIdIndex();
            idIndex = index;
        }
        return index;
    }

    private static Map<String, Class<? extends BaseNode>> buildIdIndex() {
        Map<String, Class<? extends BaseNode>> index = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Class<? extends BaseNode> nodeClass : scanAllNodeClasses()) {
            for (String id : idsOf(nodeClass)) {
                Class<? extends BaseNode> existing = index.putIfAbsent(id, nodeClass);
                if (existing != null && existing != nodeClass) {
                    ambiguous.add(id);
                }
            }
        }
        for (String id : ambiguous) {
            index.remove(id);
            log.warn("Node type id \"{}\" is claimed by more than one node class; it will fall back to class-name resolution", id);
        }
        return index;
    }

    /** The ids that should resolve to {@code nodeClass}: its simple name, plus any {@code @Node.Type} value/aliases. */
    private static List<String> idsOf(Class<? extends BaseNode> nodeClass) {
        List<String> ids = new ArrayList<>();
        ids.add(nodeClass.getSimpleName());
        Node.Type type = nodeClass.getAnnotation(Node.Type.class);
        if (type != null) {
            if (!type.value().isBlank()) {
                ids.add(type.value());
            }
            for (String alias : type.aliases()) {
                if (!alias.isBlank()) {
                    ids.add(alias);
                }
            }
        }
        return ids;
    }

    /** Every concrete {@link BaseNode} subclass on the classpath under {@link #BASE_PACKAGE}, including {@code @Node.Disabled} ones. */
    private static List<Class<? extends BaseNode>> scanAllNodeClasses() {
        List<Class<? extends BaseNode>> classes = new ArrayList<>();
        String basePath = BASE_PACKAGE.replace('.', '/');
        try {
            Enumeration<URL> roots = Thread.currentThread().getContextClassLoader().getResources(basePath);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    scanDirectory(new File(root.toURI()), BASE_PACKAGE, classes);
                } else if ("jar".equals(root.getProtocol())) {
                    scanJar(root, basePath, classes);
                }
            }
        } catch (IOException | URISyntaxException e) {
            log.error("Failed to scan for node classes under {}", BASE_PACKAGE, e);
        }
        return classes;
    }

    /** Creates a fresh instance of a discovered node class via its no-arg constructor, or null if that fails. */
    public static BaseNode instantiate(Class<? extends BaseNode> nodeClass) {
        try {
            return nodeClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            log.error("Failed to instantiate node {}", nodeClass.getName(), e);
            return null;
        }
    }

    /**
     * Creates a fresh instance of the same node type as {@code source}, with its
     * current input/output values copied over by position. Used for copy/paste: no
     * per-node-type clone() is needed since {@code configureInputs()}/{@code configureOutputs()}
     * always build the same list shape for a given class.
     *
     * <p>Only <em>persistent</em> values are carried across (see
     * {@link NodeVariable#isPersistentValue()}): computed outputs, secrets, and transient
     * runtime handles are left out, exactly as they are for save files. This keeps a value
     * resolved off an incoming edge — a secret in particular — from being copied into the
     * duplicate as a manual entry; the edge itself is re-wired by the caller.
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
            NodeVariable source = from.get(i);
            // Mirror the save-file persistence discipline (NodeVariable.isPersistentValue): only
            // manually-authored, non-secret, non-transient values are carried to the copy. Anything
            // a node computes — including a secret resolved off an incoming edge and committed onto
            // the input variable after a run — is left out, so it's never turned into a manual entry
            // (which is how a secret used to end up pasted in plaintext). Edges are re-wired
            // separately by the caller, restoring the real value source.
            if (source.isPersistentValue()) {
                to.get(i).setValue(source.getValue());
            }
        }
    }

    private static void scanDirectory(File directory, String packageName, List<Class<? extends BaseNode>> out) {
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

    private static void scanJar(URL jarUrl, String basePath, List<Class<? extends BaseNode>> out) throws IOException {
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

    /** Loads {@code className} and, if it's a concrete node type, adds it (disabled or not — the disabled filter lives in {@link #discover()}). */
    private static void tryAdd(String className, List<Class<? extends BaseNode>> out) {
        try {
            Class<?> type = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            if (BaseNode.class.isAssignableFrom(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                @SuppressWarnings("unchecked")
                Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) type;
                out.add(nodeClass);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            log.warn("Skipping unloadable class {}: {}", className, e);
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
