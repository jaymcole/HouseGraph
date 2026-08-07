package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.fixture.liba.nodes.RootLevelNode;
import io.github.jaymcole.housegraph.graph.fixture.liba.nodes.debug.RetiredNode;
import io.github.jaymcole.housegraph.graph.fixture.liba.nodes.math.FixtureAddNode;
import io.github.jaymcole.housegraph.graph.fixture.liba.nodes.values.ValuesNode;
import io.github.jaymcole.housegraph.graph.fixture.libb.nodes.OnlyInLibBNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts against the fixture node libraries under {@code graph.fixture}, never the app's own node
 * library. That is deliberate: the built-in library is being emptied into external repositories, so
 * a test bound to it would churn on every extraction — and it does not exist in this module at all.
 */
class NodeRegistryTest {

    private static final String LIB_A = "liba";
    private static final String LIB_B = "libb";

    private static final ClassLoader LOADER = NodeRegistryTest.class.getClassLoader();

    private static NodeRegistry.ScanRoot libA(String categoryPrefix) {
        return new NodeRegistry.ScanRoot(
                "io.github.jaymcole.housegraph.graph.fixture.liba.nodes", LOADER, LIB_A, categoryPrefix, null);
    }

    private static NodeRegistry.ScanRoot libB(String categoryPrefix) {
        return new NodeRegistry.ScanRoot(
                "io.github.jaymcole.housegraph.graph.fixture.libb.nodes", LOADER, LIB_B, categoryPrefix, null);
    }

    private static NodeRegistry registryOverLibA() {
        return new NodeRegistry(List.of(libA("")));
    }

    private static List<Class<? extends BaseNode>> classesOf(NodeRegistry registry) {
        return registry.discover().stream().map(NodeRegistry.Entry::nodeClass).toList();
    }

    private static NodeRegistry.Entry entryFor(NodeRegistry registry, Class<?> nodeClass) {
        return registry.discover().stream()
                .filter(entry -> entry.nodeClass() == nodeClass)
                .findFirst()
                .orElseThrow(() -> new AssertionError(nodeClass.getSimpleName() + " was not discovered"));
    }

    // --- Stable save-file type identity ---------------------------------------------------------

    @Test
    void persistentTypeIdDefaultsToSimpleClassName() {
        assertEquals("FixtureAddNode", NodeRegistry.persistentTypeId(FixtureAddNode.class),
                "an unannotated node persists under its simple class name (decoupled from its package)");
    }

    @Test
    void persistentTypeIdUsesAnExplicitNodeTypeValue() {
        assertEquals("retired-fixture", NodeRegistry.persistentTypeId(RetiredNode.class),
                "a @Node.Type value overrides the simple-class-name default");
    }

    @Test
    void resolveClassBySimpleName() {
        assertSame(FixtureAddNode.class, registryOverLibA().resolveClass("FixtureAddNode"),
                "the simple class name is the default persisted id and resolves back");
    }

    @Test
    void resolveClassByFullyQualifiedNameStillWorksForOldSaves() {
        assertSame(FixtureAddNode.class, registryOverLibA().resolveClass(FixtureAddNode.class.getName()),
                "a pre-type-id save stored the class name; it must still resolve via the fallback");
    }

    @Test
    void resolveClassByExplicitTypeIdAndAlias() {
        NodeRegistry registry = registryOverLibA();
        // A @Node.Type node resolves by its id and by any alias — the path that keeps old saves
        // loading after a class is renamed. RetiredNode is disabled yet still indexed, on purpose.
        assertSame(RetiredNode.class, registry.resolveClass("retired-fixture"), "resolves by its @Node.Type id");
        assertSame(RetiredNode.class, registry.resolveClass("legacy.retired.fixture.id"), "resolves by an alias");
        assertSame(RetiredNode.class, registry.resolveClass("RetiredNode"), "still resolves by its simple name too");
    }

    @Test
    void resolveClassReturnsNullForAnUnknownType() {
        NodeRegistry registry = registryOverLibA();
        assertNull(registry.resolveClass("com.example.NotARealNode"));
        assertNull(registry.resolveClass("no-such-id"));
        assertNull(registry.resolveClass(null));
    }

    @Test
    void aNonNodeClassNameDoesNotResolveEvenThoughItLoads() {
        // The class-name fallback loads whatever it is handed, so the BaseNode check is the only
        // thing standing between a save file and arbitrary class loading.
        assertNull(registryOverLibA().resolveClass("java.lang.String"));
    }

    // --- Discovery ------------------------------------------------------------------------------

    @Test
    void discoversNodeClassesUnderTheScanRoot() {
        List<Class<? extends BaseNode>> classes = classesOf(registryOverLibA());

        assertTrue(classes.contains(FixtureAddNode.class));
        assertTrue(classes.contains(RootLevelNode.class));
        assertTrue(classes.contains(ValuesNode.class));
    }

    @Test
    void discoveryExcludesDisabledTypesButResolutionStillFindsThem() {
        NodeRegistry registry = registryOverLibA();

        assertFalse(classesOf(registry).contains(RetiredNode.class),
                "@Node.Disabled keeps a type out of the Add-Node menu");
        assertSame(RetiredNode.class, registry.resolveClass("retired-fixture"),
                "...but a graph saved while it was enabled must still load");
    }

    @Test
    void discoveryIsScopedToTheConfiguredRoots() {
        assertFalse(classesOf(registryOverLibA()).contains(OnlyInLibBNode.class),
                "a root that was not configured must not be scanned");
    }

    @Test
    void categoryPathMatchesSubpackageWhenThereIsNoPrefix() {
        NodeRegistry registry = registryOverLibA();

        assertEquals("math", entryFor(registry, FixtureAddNode.class).categoryPath());
        assertEquals("", entryFor(registry, RootLevelNode.class).categoryPath(),
                "a node sitting in the scan root itself has no category");
    }

    @Test
    void categoryPrefixNestsALibrarysNodesUnderOneSubmenu() {
        NodeRegistry registry = new NodeRegistry(List.of(libA("liba")));

        assertEquals("liba.math", entryFor(registry, FixtureAddNode.class).categoryPath());
        assertEquals("liba", entryFor(registry, RootLevelNode.class).categoryPath(),
                "a root-level node takes the prefix alone, with no trailing separator");
    }

    @Test
    void displayNameUsesTheDisplayNameAnnotation() {
        assertEquals("Fixture Add", entryFor(registryOverLibA(), FixtureAddNode.class).displayName());
    }

    @Test
    void displayNameFallsBackToTheSimpleClassName() {
        assertEquals("RootLevelNode", entryFor(registryOverLibA(), RootLevelNode.class).displayName());
    }

    // --- Owning library -------------------------------------------------------------------------

    @Test
    void everyDiscoveredTypeCarriesTheOwningLibrary() {
        NodeRegistry registry = new NodeRegistry(List.of(libA(""), libB("libb")));

        assertEquals(LIB_A, entryFor(registry, FixtureAddNode.class).pluginId());
        assertEquals(LIB_B, entryFor(registry, OnlyInLibBNode.class).pluginId());
        assertEquals(LIB_A, registry.pluginIdOf(FixtureAddNode.class));
        assertEquals(LIB_B, registry.pluginIdOf(OnlyInLibBNode.class));
    }

    @Test
    void pluginIdOfAnUnknownClassFallsBackToCore() {
        assertEquals(NodeRegistry.CORE_PLUGIN_ID, registryOverLibA().pluginIdOf(OnlyInLibBNode.class),
                "a class from a root we are not scanning is attributed to core rather than left null");
        assertEquals(NodeRegistry.CORE_PLUGIN_ID, registryOverLibA().pluginIdOf(null));
    }

    // --- Type-id collisions between two libraries -----------------------------------------------

    @Test
    void aTypeIdClaimedByTwoLibrariesIsNotResolvedByIdAlone() {
        NodeRegistry registry = new NodeRegistry(List.of(libA(""), libB("libb")));

        assertTrue(registry.ambiguousTypeIds().contains("SharedNameNode"),
                "the collision is reported so the dependency window can surface it");
        assertNull(registry.resolveClass("SharedNameNode"),
                "resolving to an arbitrary one of the two would silently load the wrong node");
    }

    @Test
    void aCollidingTypeIdStillResolvesWhenTheSaveRecordedItsLibrary() {
        NodeRegistry registry = new NodeRegistry(List.of(libA(""), libB("libb")));

        assertSame(io.github.jaymcole.housegraph.graph.fixture.liba.nodes.dup.SharedNameNode.class,
                registry.resolveClass("SharedNameNode", LIB_A));
        assertSame(io.github.jaymcole.housegraph.graph.fixture.libb.nodes.dup.SharedNameNode.class,
                registry.resolveClass("SharedNameNode", LIB_B),
                "this is the whole reason the save format records the owning library");
    }

    @Test
    void anUnknownOwningLibraryFallsBackRatherThanFailing() {
        assertSame(FixtureAddNode.class, registryOverLibA().resolveClass("FixtureAddNode", "uninstalled-library"),
                "a library recorded in the save but not installed must not block resolving a type that is present");
    }

    // --- Invalidation ---------------------------------------------------------------------------

    @Test
    void setRootsMakesNewlyInstalledTypesVisible() {
        NodeRegistry registry = registryOverLibA();
        assertFalse(classesOf(registry).contains(OnlyInLibBNode.class));
        assertNull(registry.resolveClass("OnlyInLibBNode"));

        registry.setRoots(List.of(libA(""), libB("libb")));

        assertTrue(classesOf(registry).contains(OnlyInLibBNode.class),
                "installing a library must not require a restart to show up in the menu");
        assertSame(OnlyInLibBNode.class, registry.resolveClass("OnlyInLibBNode"),
                "the id index is rebuilt too, not just the menu — it used to be cached forever");
    }

    @Test
    void setRootsAlsoDropsTypesThatAreNoLongerInstalled() {
        NodeRegistry registry = new NodeRegistry(List.of(libA(""), libB("libb")));
        assertTrue(classesOf(registry).contains(OnlyInLibBNode.class));

        registry.setRoots(List.of(libA("")));

        assertFalse(classesOf(registry).contains(OnlyInLibBNode.class));
        assertNull(registry.resolveClass("OnlyInLibBNode"));
    }

    // --- Instantiation and duplication ----------------------------------------------------------

    @Test
    void instantiateBuildsAWorkingNode() {
        BaseNode node = NodeRegistry.instantiate(FixtureAddNode.class);
        assertTrue(node instanceof FixtureAddNode);
    }

    @Test
    void duplicateCopiesManuallyAuthoredValues() {
        ValuesNode original = new ValuesNode();
        original.authored.setValue("typed by hand");

        BaseNode copy = NodeRegistry.duplicate(original);

        assertTrue(copy instanceof ValuesNode);
        assertEquals("typed by hand", ((ValuesNode) copy).authored.getValue());
    }

    @Test
    void duplicateDoesNotCopyComputedSecretOrTransientValues() {
        // Mirrors the save-file persistence discipline (NodeVariable.isPersistentValue). The secret
        // case is a regression guard: copying a node whose value had been resolved off a secret used
        // to paste that secret in plaintext as a manual entry.
        ValuesNode original = new ValuesNode();
        original.computed.setValue("pulled off an edge");
        original.secret.setValue("hunter2");
        original.handle.setValue("live-connection");

        ValuesNode copy = (ValuesNode) NodeRegistry.duplicate(original);

        assertNull(copy.computed.getValue(), "a computed value recomputes; it is never carried across");
        assertNull(copy.secret.getValue(), "a secret must never transfer into a duplicate");
        assertNull(copy.handle.getValue(), "a transient runtime handle belongs to the original only");
    }

    @Test
    void duplicateIsIndependentFromTheOriginal() {
        ValuesNode original = new ValuesNode();
        original.authored.setValue("one");

        ValuesNode copy = (ValuesNode) NodeRegistry.duplicate(original);
        assertNotSame(original, copy);

        copy.authored.setValue("two");

        assertEquals("one", original.authored.getValue());
        assertEquals("two", copy.authored.getValue());
    }
}
