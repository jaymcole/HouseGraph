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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers concrete {@link BaseNode} subclasses so the UI can build an "Add Node" menu straight
 * from the package structure instead of maintaining a hardcoded list. Dropping a new node class
 * under a scanned package is enough for it to show up; nothing needs to be registered.
 * <p>
 * <b>Why this is an instance.</b> It used to be entirely static, over a single hardcoded package,
 * with an id index cached forever. That was right while every node type shipped in the app and the
 * set could not change during a run. Now node libraries are installed and removed at runtime, so a
 * registry has to carry mutable state — which roots to scan, which class loader can load them, and
 * an index that can be <em>thrown away</em> ({@link #setRoots}). A single shared static index with
 * no invalidation is precisely what would make a freshly installed library invisible until restart.
 * <p>
 * {@link #persistentTypeId}, {@link #instantiate} and {@link #duplicate} stay static: they are pure
 * reflection over a class the caller already holds and need no registry state.
 *
 * <h2>Scan roots</h2>
 * Each {@link ScanRoot} pairs a package to scan with the loader that can load it, the library that
 * owns whatever is found there, and the menu category those nodes nest under. The app's own node
 * library is just another root ({@link ScanRoot#core}), which keeps the built-in and installed
 * cases on one code path.
 *
 * <h2>Save-file identity</h2>
 * A node is written to a save file by a stable <em>type id</em> ({@link #persistentTypeId}), not its
 * fully-qualified class name. The id defaults to the simple class name — which already survives
 * moving the class between packages — and a class can pin a different id (or extra
 * {@link io.github.jaymcole.housegraph.annotations.Node.Type aliases}) with {@code @Node.Type} to
 * stay resolvable across a rename.
 * <p>
 * Because two independently-written libraries can pick the same id, {@link #resolveClass(String,
 * String)} takes the owning library recorded in the save file and searches that library first. A
 * save that records its libraries is therefore immune to a collision; only a legacy save that does
 * not is exposed, and even then an unresolved type is preserved rather than dropped.
 */
public final class NodeRegistry {

    private static final Logger log = Log.get(NodeRegistry.class);

    /** The owner id recorded for node types that ship with the app itself. */
    public static final String CORE_PLUGIN_ID = "core";

    /** The package the app's own node library lives under. */
    public static final String CORE_BASE_PACKAGE = "io.github.jaymcole.housegraph.graph.nodes";

    /**
     * One place to look for node classes.
     *
     * @param packageName     the package to scan, and the prefix categories are derived relative to
     * @param loader          the class loader that can load classes found here
     * @param pluginId        the library owning what is found here; {@link #CORE_PLUGIN_ID} for built-ins
     * @param categoryPrefix  the Add-Node menu category these nodes nest under; empty for no nesting
     * @param jar             the jar to enumerate directly, or null to enumerate {@code loader}'s
     *                        resources (which is how the app's own classpath is walked)
     */
    public record ScanRoot(String packageName,
                           ClassLoader loader,
                           String pluginId,
                           String categoryPrefix,
                           Path jar) {

        public ScanRoot {
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(categoryPrefix, "categoryPrefix");
        }

        /** The app's own node library, whose categories are its subpackages exactly as before. */
        public static ScanRoot core(ClassLoader loader) {
            return new ScanRoot(CORE_BASE_PACKAGE, loader, CORE_PLUGIN_ID, "", null);
        }
    }

    /**
     * One discovered node type.
     *
     * @param nodeClass    the concrete BaseNode subclass
     * @param categoryPath its menu category, dot-separated; empty to sit at the top level
     * @param displayName  {@code @Display.Name} if present, else the simple class name
     * @param pluginId     the library that owns it; {@link #CORE_PLUGIN_ID} for built-ins
     */
    public record Entry(Class<? extends BaseNode> nodeClass, String categoryPath, String displayName, String pluginId) {
    }

    /** One class found during a scan, with the root-derived facts that can't be read off the class. */
    private record Found(Class<? extends BaseNode> nodeClass, String pluginId, String categoryPath) {
    }

    /**
     * Everything derived from one scan, replaced wholesale rather than mutated. Holding the lookups
     * in a single immutable object is what makes {@link #setRoots} safe: readers either see the
     * complete old index or the complete new one, never a half-rebuilt mixture.
     */
    private record Index(List<Found> found,
                         Map<String, Class<? extends BaseNode>> byId,
                         Map<String, Map<String, Class<? extends BaseNode>>> byPluginThenId,
                         Map<Class<?>, String> ownerByClass,
                         List<String> ambiguousIds) {
    }

    private final Object rebuildLock = new Object();
    private volatile List<ScanRoot> roots;
    private volatile Index index;

    /**
     * @param roots where to look for node classes, in priority order
     */
    public NodeRegistry(List<ScanRoot> roots) {
        this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    }

    /**
     * Replaces the scan roots and discards the cached index, so the next lookup re-scans. Call after
     * installing, updating, removing, enabling or disabling a node library.
     *
     * @param roots the new scan roots
     */
    public void setRoots(List<ScanRoot> roots) {
        synchronized (rebuildLock) {
            this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
            this.index = null;
        }
    }

    /** The scan roots currently in effect. */
    public List<ScanRoot> getRoots() {
        return roots;
    }

    /**
     * Every node type that should appear in the Add-Node menu, sorted by category then class name.
     * {@code @Node.Disabled} types are excluded here but stay resolvable by {@link #resolveClass},
     * so a graph saved while a type was enabled still loads after it's been disabled.
     *
     * @return the discoverable node types
     */
    public List<Entry> discover() {
        List<Entry> entries = new ArrayList<>();
        for (Found found : index().found()) {
            if (found.nodeClass().isAnnotationPresent(Node.Disabled.class)) {
                continue;
            }
            entries.add(new Entry(found.nodeClass(), found.categoryPath(),
                    displayNameOf(found.nodeClass()), found.pluginId()));
        }
        entries.sort(Comparator.comparing(Entry::categoryPath).thenComparing(entry -> entry.nodeClass().getSimpleName()));
        return entries;
    }

    /**
     * Resolves a saved node {@code type} back to a loadable class, or null if nothing matches.
     * <p>
     * Resolution order, and the reason for it: the library recorded in the save file wins, so a type
     * id claimed by two installed libraries still resolves to the one the graph was actually built
     * with; then a unique match across everything; then core, which is allowed to win a collision
     * with an installed library because a built-in is the more predictable answer; then
     * fully-qualified-class-name resolution, which is what saves written before type ids existed
     * stored.
     *
     * @param type     the saved type id, or a class name from a pre-type-id save
     * @param pluginId the library recorded alongside it, or null if the save didn't record one
     * @return the loadable node class, or null
     */
    public Class<? extends BaseNode> resolveClass(String type, String pluginId) {
        if (type == null) {
            return null;
        }
        Index current = index();

        if (pluginId != null) {
            Map<String, Class<? extends BaseNode>> owned = current.byPluginThenId().get(pluginId);
            if (owned != null) {
                Class<? extends BaseNode> byOwner = owned.get(type);
                if (byOwner != null) {
                    return byOwner;
                }
            }
        }

        Class<? extends BaseNode> unique = current.byId().get(type);
        if (unique != null) {
            return unique;
        }

        Map<String, Class<? extends BaseNode>> core = current.byPluginThenId().get(CORE_PLUGIN_ID);
        if (core != null) {
            Class<? extends BaseNode> byCore = core.get(type);
            if (byCore != null) {
                return byCore;
            }
        }

        return resolveByClassName(type);
    }

    /**
     * Back-compat overload for callers with no owning-library information.
     *
     * @param type the saved type id or class name
     * @return the loadable node class, or null
     */
    public Class<? extends BaseNode> resolveClass(String type) {
        return resolveClass(type, null);
    }

    /**
     * The library that owns {@code nodeClass}. Falls back to the jar the class was actually loaded
     * from (authoritative — an author cannot misdeclare it) for a class that arrived through
     * {@link #resolveClass}'s class-name path rather than a scan, and finally to
     * {@link #CORE_PLUGIN_ID}.
     *
     * @param nodeClass the node type
     * @return its owning library id; never null
     */
    public String pluginIdOf(Class<? extends BaseNode> nodeClass) {
        if (nodeClass == null) {
            return CORE_PLUGIN_ID;
        }
        String owner = index().ownerByClass().get(nodeClass);
        if (owner != null) {
            return owner;
        }
        return ownerByCodeSource(nodeClass);
    }

    /**
     * Type ids claimed by more than one node class, which therefore can't be resolved from a save
     * that doesn't record its libraries. Surfaced in the dependency window rather than only logged,
     * because the user is the one who can fix it by removing a library.
     *
     * @return the colliding type ids, sorted
     */
    public List<String> ambiguousTypeIds() {
        return index().ambiguousIds();
    }

    // --- Static helpers: pure reflection, no registry state ------------------------

    /**
     * The stable id a node type is written under in save files: its {@code @Node.Type} value if it
     * declares one, otherwise its simple class name. The simple name already survives moving the
     * class between packages; {@code @Node.Type} is for surviving a class rename. See
     * {@link Node.Type}.
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

    // --- Index ---------------------------------------------------------------------

    private Index index() {
        Index current = index;
        if (current != null) {
            return current;
        }
        synchronized (rebuildLock) {
            if (index == null) {
                index = buildIndex(roots);
            }
            return index;
        }
    }

    private static Index buildIndex(List<ScanRoot> roots) {
        List<Found> found = new ArrayList<>();
        for (ScanRoot root : roots) {
            scan(root, found);
        }

        Map<String, Class<? extends BaseNode>> byId = new HashMap<>();
        Map<String, Map<String, Class<? extends BaseNode>>> byPluginThenId = new HashMap<>();
        Map<Class<?>, String> ownerByClass = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();

        for (Found entry : found) {
            ownerByClass.putIfAbsent(entry.nodeClass(), entry.pluginId());
            Map<String, Class<? extends BaseNode>> owned =
                    byPluginThenId.computeIfAbsent(entry.pluginId(), key -> new HashMap<>());
            for (String id : idsOf(entry.nodeClass())) {
                owned.putIfAbsent(id, entry.nodeClass());
                Class<? extends BaseNode> existing = byId.putIfAbsent(id, entry.nodeClass());
                if (existing != null && existing != entry.nodeClass()) {
                    ambiguous.add(id);
                }
            }
        }

        List<String> ambiguousIds = new ArrayList<>(ambiguous);
        ambiguousIds.sort(Comparator.naturalOrder());
        for (String id : ambiguousIds) {
            byId.remove(id);
            log.warn("Node type id \"{}\" is claimed by more than one node class; saves that record their "
                    + "owning library still resolve it, others fall back to class-name resolution", id);
        }

        return new Index(List.copyOf(found), Map.copyOf(byId), Map.copyOf(byPluginThenId),
                Map.copyOf(ownerByClass), List.copyOf(ambiguousIds));
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

    // --- Scanning ------------------------------------------------------------------

    private static void scan(ScanRoot root, List<Found> out) {
        if (root.jar() != null) {
            scanJarFile(root, out);
        } else {
            scanClasspath(root, out);
        }
    }

    /**
     * Enumerates a known jar directly. Faster than walking the loader's resources (one open instead
     * of a full classpath enumeration per root), and it sidesteps the {@link JarURLConnection} cache
     * entirely — see the note in {@link #scanJarUrl}.
     */
    private static void scanJarFile(ScanRoot root, List<Found> out) {
        String basePath = root.packageName().replace('.', '/');
        try (JarFile jarFile = new JarFile(root.jar().toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (isCandidateClassEntry(name, basePath)) {
                    tryAdd(name.substring(0, name.length() - ".class".length()).replace('/', '.'), root, out);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan node library jar {}", root.jar(), e);
        }
    }

    private static void scanClasspath(ScanRoot root, List<Found> out) {
        String basePath = root.packageName().replace('.', '/');
        try {
            Enumeration<URL> urls = root.loader().getResources(basePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(new File(url.toURI()), root.packageName(), root, out);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarUrl(url, basePath, root, out);
                }
            }
        } catch (IOException | URISyntaxException e) {
            log.error("Failed to scan for node classes under {}", root.packageName(), e);
        }
    }

    private static void scanDirectory(File directory, String packageName, ScanRoot root, List<Found> out) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), root, out);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
                String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
                tryAdd(packageName + "." + simpleName, root, out);
            }
        }
    }

    private static void scanJarUrl(URL jarUrl, String basePath, ScanRoot root, List<Found> out) throws IOException {
        JarURLConnection connection = (JarURLConnection) jarUrl.openConnection();
        // Opt out of the JAR cache before asking for the file. With caching on (the default),
        // getJarFile() hands back the very JarFile the class loader is serving classes from, and
        // closing it below would break every subsequent load from that jar — and on Windows leave
        // handle state that stops the file being deleted or replaced. An uncached open gives us a
        // private JarFile that is ours to close.
        connection.setUseCaches(false);
        try (JarFile jarFile = connection.getJarFile()) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                String name = jarEntries.nextElement().getName();
                if (isCandidateClassEntry(name, basePath)) {
                    tryAdd(name.substring(0, name.length() - ".class".length()).replace('/', '.'), root, out);
                }
            }
        }
    }

    /** Nested and anonymous classes ({@code $} in the name) are never node types, so they're skipped. */
    private static boolean isCandidateClassEntry(String entryName, String basePath) {
        return entryName.startsWith(basePath + "/") && entryName.endsWith(".class") && !entryName.contains("$");
    }

    /** Loads {@code className} and, if it's a concrete node type, records it (disabled or not — that filter lives in {@link #discover()}). */
    private static void tryAdd(String className, ScanRoot root, List<Found> out) {
        try {
            // initialize = false: discovering a node must not run its static initializer. That runs
            // at first instantiate() instead, which is why a library registering a ValueEditors or
            // TypeConverters entry from a static block only takes effect once one of its nodes exists.
            Class<?> type = Class.forName(className, false, root.loader());
            if (BaseNode.class.isAssignableFrom(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                @SuppressWarnings("unchecked")
                Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) type;
                out.add(new Found(nodeClass, root.pluginId(), categoryOf(nodeClass, root)));
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // A library whose own transitive dependency is missing shows up here. Warn and skip the
            // one class rather than failing the whole scan and taking every other node down with it.
            log.warn("Skipping unloadable class {}: {}", className, e);
        }
    }

    /**
     * A node's menu category: its subpackage path below the root, nested under the root's category
     * prefix. For the app's own library the prefix is empty and the root is the base package, so
     * this is exactly the historical behaviour; for an installed library the prefix groups all of
     * its nodes under one submenu.
     */
    private static String categoryOf(Class<?> nodeClass, ScanRoot root) {
        String pkg = nodeClass.getPackageName();
        String relative;
        if (pkg.equals(root.packageName())) {
            relative = "";
        } else if (pkg.startsWith(root.packageName() + ".")) {
            relative = pkg.substring(root.packageName().length() + 1);
        } else {
            relative = pkg;
        }
        String prefix = root.categoryPrefix();
        if (prefix.isEmpty()) {
            return relative;
        }
        return relative.isEmpty() ? prefix : prefix + "." + relative;
    }

    private static String displayNameOf(Class<? extends BaseNode> nodeClass) {
        Display.Name displayName = nodeClass.getAnnotation(Display.Name.class);
        if (displayName != null && !displayName.value().isBlank()) {
            return displayName.value();
        }
        return nodeClass.getSimpleName();
    }

    // --- Fallbacks -----------------------------------------------------------------

    /**
     * Fully-qualified-class-name resolution, for saves written before type ids existed. Tried
     * through each root's loader in turn, since the class could live in the app or in any installed
     * library.
     */
    private Class<? extends BaseNode> resolveByClassName(String className) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        for (ScanRoot root : roots) {
            loaders.add(root.loader());
        }
        loaders.add(NodeRegistry.class.getClassLoader());
        for (ClassLoader loader : loaders) {
            try {
                Class<?> loaded = Class.forName(className, false, loader);
                if (BaseNode.class.isAssignableFrom(loaded) && !loaded.isInterface()
                        && !Modifier.isAbstract(loaded.getModifiers())) {
                    @SuppressWarnings("unchecked")
                    Class<? extends BaseNode> nodeClass = (Class<? extends BaseNode>) loaded;
                    return nodeClass;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // try the next loader
            }
        }
        return null;
    }

    /**
     * The library whose jar a class was actually loaded from. Authoritative where the scan-time map
     * misses, because it names the file the bytes came from rather than anything an author declared.
     */
    private String ownerByCodeSource(Class<?> nodeClass) {
        Path source;
        try {
            var protectionDomain = nodeClass.getProtectionDomain();
            var codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            source = location == null ? null : Path.of(location.toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return CORE_PLUGIN_ID;
        }
        if (source == null) {
            return CORE_PLUGIN_ID;
        }
        for (ScanRoot root : roots) {
            if (root.jar() != null && root.jar().equals(source)) {
                return root.pluginId();
            }
        }
        return CORE_PLUGIN_ID;
    }
}
