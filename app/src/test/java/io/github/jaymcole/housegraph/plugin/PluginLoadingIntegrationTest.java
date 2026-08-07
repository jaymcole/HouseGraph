package io.github.jaymcole.housegraph.plugin;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end check for out-of-tree node loading: a node class that exists <b>only</b> inside a
 * jar — compiled here at test time, deliberately not on the test classpath — is discovered,
 * categorised, attributed to its library, and instantiated.
 *
 * <p>Compiling rather than reusing a fixture class matters. A fixture would already be on the
 * parent class loader, and parent-first delegation would serve it from there, so the test would
 * pass without ever proving the jar was read. Compiling into a temp directory is the only way to be
 * sure the bytes really came from the library.
 */
class PluginLoadingIntegrationTest {

    private static final String NODE_SOURCE = """
            package com.example.probe.nodes;

            import io.github.jaymcole.housegraph.annotations.Display;
            import io.github.jaymcole.housegraph.annotations.Node;
            import io.github.jaymcole.housegraph.graph.BaseNode;
            import io.github.jaymcole.housegraph.graph.NodeVariable;
            import io.github.jaymcole.housegraph.graph.ProcessContext;

            @Display.Name("Probe")
            @Node.Type("probe.ProbeNode")
            public class ProbeNode extends BaseNode {
                private final NodeVariable<String> out = new NodeVariable<>("Out", String.class);

                @Override public void configureInputs() { }
                @Override public void configureOutputs() { addOutput(out); }
                @Override public void process(ProcessContext ctx) { out.setValue("probed"); }
            }
            """;

    /** Compiles the node into {@code classesDir} against the current classpath. */
    private static void compileProbeNode(Path classesDir) throws IOException {
        Path source = classesDir.resolve("ProbeNode.java");
        Files.createDirectories(classesDir);
        Files.writeString(source, NODE_SOURCE, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK, not a JRE");
        int result = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                source.toString());
        assertEquals(0, result, "the probe node should compile against housegraph-api");
        Files.delete(source);
    }

    /** Packages the compiled class plus a manifest into a jar at the catalog's expected location. */
    private static PluginCatalog installProbeLibrary(Path temp, String id, String categoryPrefix) throws IOException {
        Path classes = temp.resolve("classes");
        compileProbeNode(classes);

        PluginCatalog catalog = PluginCatalog.loadFrom(temp.resolve("plugins.json"), temp.resolve("plugins"));
        PluginCatalog.Installed installed = new PluginCatalog.Installed(
                id, "Probe Library", "1.0.0", "https://github.com/example/" + id, "0.2",
                List.of("com.example.probe.nodes"), categoryPrefix, null, true);
        catalog.put(installed);

        Path jar = catalog.jarFor(installed);
        Files.createDirectories(jar.getParent());
        JSONObject manifest = new JSONObject()
                .put("manifestVersion", 1)
                .put("id", id)
                .put("name", "Probe Library")
                .put("version", "1.0.0")
                .put("apiVersion", "0.2")
                .put("repository", "https://github.com/example/" + id)
                .put("nodePackages", List.of("com.example.probe.nodes"))
                .put("categoryPrefix", categoryPrefix);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(PluginManifest.ENTRY));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            Path classFile = classes.resolve("com/example/probe/nodes/ProbeNode.class");
            zip.putNextEntry(new ZipEntry("com/example/probe/nodes/ProbeNode.class"));
            zip.write(Files.readAllBytes(classFile));
            zip.closeEntry();
        }
        return catalog;
    }

    @Test
    void aNodeThatExistsOnlyInsideAJarIsDiscoveredInstantiatedAndAttributed(@TempDir Path temp) throws Exception {
        // Precondition: the class genuinely is not reachable without the library.
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.example.probe.nodes.ProbeNode", false, getClass().getClassLoader()),
                "if this resolves, the test is not proving anything about jar loading");

        PluginCatalog catalog = installProbeLibrary(temp, "housegraph-probe", "probe");

        try (PluginLoader loader = PluginLoader.from(catalog, getClass().getClassLoader())) {
            NodeRegistry registry = new NodeRegistry(loader.scanRoots());

            NodeRegistry.Entry probe = registry.discover().stream()
                    .filter(entry -> entry.displayName().equals("Probe"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the library's node did not reach the Add-Node menu"));

            assertEquals("probe", probe.categoryPath(), "it nests under its library's category");
            assertEquals("housegraph-probe", probe.pluginId());
            assertEquals("housegraph-probe", registry.pluginIdOf(probe.nodeClass()));

            // Resolvable by the id a save file would record, and actually constructible.
            assertSame(probe.nodeClass(), registry.resolveClass("probe.ProbeNode", "housegraph-probe"));
            BaseNode instance = NodeRegistry.instantiate(probe.nodeClass());
            assertNotNull(instance);
            assertEquals("Probe", instance.getName());
            assertEquals(1, instance.getOutputs().size());
        }
    }

    @Test
    void theAppsOwnNodesAreStillThereAlongsideTheLibrarys(@TempDir Path temp) throws Exception {
        PluginCatalog catalog = installProbeLibrary(temp, "housegraph-probe", "probe");

        try (PluginLoader loader = PluginLoader.from(catalog, getClass().getClassLoader())) {
            List<NodeRegistry.Entry> entries = new NodeRegistry(loader.scanRoots()).discover();

            assertTrue(entries.stream().anyMatch(e -> e.pluginId().equals(NodeRegistry.CORE_PLUGIN_ID)),
                    "installing a library must not displace the built-in node library");
            assertTrue(entries.stream().anyMatch(e -> e.pluginId().equals("housegraph-probe")));
        }
    }

    @Test
    void disablingALibraryRemovesItsNodesWithoutARestart(@TempDir Path temp) throws Exception {
        PluginCatalog catalog = installProbeLibrary(temp, "housegraph-probe", "probe");

        PluginLoader first = PluginLoader.from(catalog, getClass().getClassLoader());
        NodeRegistry registry = new NodeRegistry(first.scanRoots());
        assertTrue(registry.discover().stream().anyMatch(e -> e.displayName().equals("Probe")));
        first.close();

        catalog.setEnabled("housegraph-probe", false);
        try (PluginLoader second = PluginLoader.from(catalog, getClass().getClassLoader())) {
            registry.setRoots(second.scanRoots());

            assertFalse(registry.discover().stream().anyMatch(e -> e.displayName().equals("Probe")),
                    "the cached index used to survive forever, which is what made this impossible");
        }
    }

    @Test
    void anUninstalledLibrarysNodeStillResolvesFromASaveOnceReinstalled(@TempDir Path temp) throws Exception {
        // The round trip the whole refactor is about: a save records "probe.ProbeNode" owned by
        // "housegraph-probe"; without the library it becomes a MissingNode, and reinstalling brings
        // the real node back.
        PluginCatalog catalog = installProbeLibrary(temp, "housegraph-probe", "probe");

        PluginLoader with = PluginLoader.from(catalog, getClass().getClassLoader());
        NodeRegistry registry = new NodeRegistry(with.scanRoots());
        assertNotNull(registry.resolveClass("probe.ProbeNode", "housegraph-probe"));
        with.close();

        catalog.remove("housegraph-probe");
        try (PluginLoader without = PluginLoader.from(catalog, getClass().getClassLoader())) {
            registry.setRoots(without.scanRoots());
            assertEquals(null, registry.resolveClass("probe.ProbeNode", "housegraph-probe"),
                    "with the library gone the type is unresolvable, which is what produces a MissingNode");
        }

        catalog.put(new PluginCatalog.Installed("housegraph-probe", "Probe Library", "1.0.0",
                null, "0.2", List.of("com.example.probe.nodes"), "probe", null, true));
        try (PluginLoader again = PluginLoader.from(catalog, getClass().getClassLoader())) {
            registry.setRoots(again.scanRoots());
            assertNotNull(registry.resolveClass("probe.ProbeNode", "housegraph-probe"),
                    "reinstalling restores the real node");
        }
    }
}
