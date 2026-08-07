package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Serves the classes of every enabled node library, and describes where {@code NodeRegistry} should
 * look for nodes inside them.
 *
 * <h2>One shared, parent-first loader</h2>
 * All enabled jars go into a single {@link URLClassLoader} whose parent is the application loader.
 * A loader per library was rejected, and the deciding argument is logging.
 * <p>
 * SLF4J 2 binds <em>once</em>, via {@code ServiceLoader} against {@code LoggerFactory}'s own loader
 * — the app loader, which finds this project's bundled provider and only that. Under parent-first
 * delegation a library's {@code org.slf4j} references resolve to the parent's classes, so a
 * library that embeds something chatty (a Discord gateway, an mDNS stack) has its logs land in the
 * same {@code LogManager} pipeline as everything else. Under child-first, or with a library that
 * bundles its own {@code org.slf4j}, you get a second binding initialised separately, routing into
 * a second {@code LogManager} that has no sinks attached — and the library's logs vanish with no
 * error anywhere. That is a miserable thing to debug, so the loader graph is arranged to make it
 * impossible.
 * <p>
 * The two things a per-library loader would have bought are covered elsewhere: dependency isolation
 * comes from libraries shading and relocating what they bundle, and owner identity comes from
 * {@code NodeRegistry} recording it during the scan.
 * <p>
 * Note that {@code instanceof NodeContentProvider} works either way — that interface comes from the
 * parent loader in both designs — which is a pleasant property of parent-first, not an accident.
 *
 * <h2>Lifecycle</h2>
 * A loader holds an open handle on each jar. On Windows an open jar can be neither deleted nor
 * overwritten, so the old loader is {@link #close() closed} before a new one is built, and installs
 * always write to a version-stamped path rather than replacing a file in use.
 */
public final class PluginLoader implements AutoCloseable {

    private static final Logger log = Log.get(PluginLoader.class);

    private final URLClassLoader classLoader;
    private final List<NodeRegistry.ScanRoot> scanRoots;

    private PluginLoader(URLClassLoader classLoader, List<NodeRegistry.ScanRoot> scanRoots) {
        this.classLoader = classLoader;
        this.scanRoots = List.copyOf(scanRoots);
    }

    /**
     * Builds a loader over every enabled library in {@code catalog}, plus the scan root for the
     * app's own node library.
     *
     * @param catalog the installed libraries
     * @param parent  the loader to delegate to — the application loader in the app
     * @return a loader; never null, even when nothing is installed
     */
    public static PluginLoader from(PluginCatalog catalog, ClassLoader parent) {
        List<URL> urls = new ArrayList<>();
        List<PluginCatalog.Installed> usable = new ArrayList<>();

        for (PluginCatalog.Installed installed : catalog.enabled()) {
            Path jar = catalog.jarFor(installed);
            if (!Files.isRegularFile(jar)) {
                // Recorded but missing: the file was deleted, or an install was interrupted. Skip
                // it and carry on — its nodes become MissingNode placeholders, which preserve the
                // user's graph, and the dependency window can offer a reinstall.
                log.warn("Node library \"{}\" is installed but its jar is missing at {}", installed.id(), jar);
                continue;
            }
            try {
                urls.add(jar.toUri().toURL());
                usable.add(installed);
            } catch (MalformedURLException e) {
                log.error("Could not add node library \"{}\" to the class path", installed.id(), e);
            }
        }

        // The named constructor, so a stack trace out of a library says where the class came from.
        URLClassLoader loader = new URLClassLoader("housegraph-plugins", urls.toArray(URL[]::new), parent);

        List<NodeRegistry.ScanRoot> roots = new ArrayList<>();
        // Core first, so a type id core also claims resolves to the built-in when a save file did
        // not record which library it came from.
        roots.add(NodeRegistry.ScanRoot.core(parent));
        for (PluginCatalog.Installed installed : usable) {
            Path jar = catalog.jarFor(installed);
            for (String nodePackage : installed.nodePackages()) {
                roots.add(new NodeRegistry.ScanRoot(
                        nodePackage, loader, installed.id(), installed.categoryPrefix(), jar));
            }
        }

        if (!usable.isEmpty()) {
            log.info("Loaded {} node librar{}: {}", usable.size(), usable.size() == 1 ? "y" : "ies",
                    usable.stream().map(PluginCatalog.Installed::id).toList());
        }
        return new PluginLoader(loader, roots);
    }

    /** The loader serving installed libraries' classes. Install as the context loader before use. */
    public ClassLoader classLoader() {
        return classLoader;
    }

    /** Where {@code NodeRegistry} should look for node classes: core, then each enabled library. */
    public List<NodeRegistry.ScanRoot> scanRoots() {
        return scanRoots;
    }

    /**
     * Releases the handles this loader holds on its jars. Must be called before rebuilding, or an
     * uninstalled jar can't be deleted on Windows.
     */
    @Override
    public void close() {
        try {
            classLoader.close();
        } catch (IOException e) {
            log.warn("Could not close the node-library class loader: {}", e.toString());
        }
    }
}
