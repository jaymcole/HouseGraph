package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * How a Module Input or Module Output declares the type of the value crossing the boundary: that
 * the declaration alone rebuilds the port, that it survives a save with nothing wired to the
 * node, and that a type this machine does not have degrades instead of throwing.
 */
class ModuleDataBoundaryNodeTest {

    /** A type that is neither in the curated list nor a JDK class — the stand-in for a plugin's type. */
    record Reading(String sensor, double value) {
    }

    @Test
    void theDeclaredTypeIsWhatThePortIsBuiltWith() {
        for (ModuleDataBoundaryNode node : freshPair()) {
            node.setDeclaredType(Boolean.class);

            assertEquals(Boolean.class, node.getBoundaryPort().type, node.getClass().getSimpleName());
        }
    }

    @Test
    void aTypeAndNameRoundTripThroughSaveAndLoadWithNothingWired() {
        for (ModuleDataBoundaryNode node : freshPair()) {
            node.setDeclaredName("temperature");
            node.setDeclaredType(Float.class);

            ModuleDataBoundaryNode loaded = fresh(node.getClass());
            loaded.loadState(node.saveState());

            // Read the port straight after loadState: nothing has ever been wired to this node, so
            // the shape can only have come from the saved state.
            assertEquals("temperature", loaded.getDeclaredName());
            assertEquals(Float.class, loaded.getBoundaryPort().type, node.getClass().getSimpleName());
        }
    }

    @Test
    void aTypeOutsideTheCuratedListIsCarriedByClassName() {
        ModuleInputNode node = new ModuleInputNode();
        node.setDeclaredType(Reading.class);

        assertEquals(Reading.class.getName(), node.saveState().get("type"));

        ModuleInputNode loaded = new ModuleInputNode();
        loaded.loadState(node.saveState());

        assertEquals(Reading.class, loaded.getBoundaryPort().type);
        assertTrue(loaded.isDeclaredTypeResolved());
    }

    @Test
    void aJdkTypeOutsideTheCuratedListResolvesToo() {
        ModuleOutputNode node = new ModuleOutputNode();
        node.setDeclaredType(Duration.class.getName());

        assertEquals(Duration.class, node.getBoundaryPort().type);
    }

    @Test
    void anUnresolvableTypeDegradesToObjectAndIsSavedUnchanged() {
        String missing = "com.example.plugin.NotInstalledHere";

        for (ModuleDataBoundaryNode node : freshPair()) {
            String kind = node.getClass().getSimpleName();
            assertDoesNotThrow(() -> node.loadState(Map.of("name", "reading", "type", missing)), kind);

            assertEquals(Object.class, node.getBoundaryPort().type, kind + " falls back so a port still exists");
            assertFalse(node.isDeclaredTypeResolved(), kind);
            assertEquals(missing, node.getDeclaredTypeName(), kind + " keeps what was declared");
            assertEquals(missing, node.saveState().get("type"),
                    kind + " must not rewrite a declaration it merely cannot read here");
        }
        assertNull(ModuleDataBoundaryNode.resolveType(missing));
    }

    @Test
    void changingTheTypeRebuildsThePort() {
        ModuleInputNode node = new ModuleInputNode();
        NodeVariable<?> before = node.getBoundaryPort();
        assertEquals(String.class, before.type, "the default declaration");

        node.setDeclaredType(Integer.class);

        assertNotSame(before, node.getBoundaryPort(), "a NodeVariable's type is final, so the port is recreated");
        assertEquals(Integer.class, node.getBoundaryPort().type);
        assertEquals(1, node.getOutputs().size(), "rebuilding must not leave the old port behind");
    }

    @Test
    void redeclaringTheSameTypeDoesNotRebuildThePort() {
        ModuleOutputNode node = new ModuleOutputNode();
        node.setDeclaredType(Integer.class);
        NodeVariable<?> port = node.getBoundaryPort();

        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> rebuilds[0]++);
        node.setDeclaredType(Integer.class);
        node.setDeclaredType("  java.lang.Integer  ");

        assertEquals(0, rebuilds[0], "an unchanged declaration is a no-op, whitespace included");
        assertEquals(port, node.getBoundaryPort());
    }

    @Test
    void aChooserEchoingTheRebuiltTypeBackDoesNotRecurse() {
        // The real loop this guards: rebuilding the ports rebuilds the view, whose type chooser
        // fires its handler with the value just pushed into it. Here the ports-changed listener
        // stands in for that view.
        ModuleInputNode node = new ModuleInputNode();
        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> {
            rebuilds[0]++;
            node.setDeclaredType(node.getDeclaredTypeName());
        });

        node.setDeclaredType(Double.class);

        assertEquals(1, rebuilds[0], "the echo must settle, not cascade");
        assertEquals(Double.class, node.getBoundaryPort().type);
    }

    @Test
    void aReentrantChangeDuringARebuildIsIgnoredRatherThanNested() {
        ModuleInputNode node = new ModuleInputNode();
        int[] rebuilds = {0};
        node.setPortsChangedListener(() -> {
            rebuilds[0]++;
            node.setDeclaredType(Boolean.class);
        });

        node.setDeclaredType(Integer.class);

        assertEquals(1, rebuilds[0]);
        assertEquals(Integer.class, node.getBoundaryPort().type, "the change that was actually applied wins");
    }

    @Test
    void aBlankOrMissingTypeDeclarationFallsBackToTheDefault() {
        ModuleInputNode node = new ModuleInputNode();
        node.setDeclaredType(Integer.class);

        node.loadState(Map.of("name", "value"));

        assertEquals(String.class, node.getBoundaryPort().type);
        assertEquals(String.class.getName(), node.getDeclaredTypeName());
    }

    @Test
    void theCuratedListIsAShortcut_notTheSetOfDeclarableTypes() {
        // Locks the curation down: it is deliberately the converter matrix's participants plus
        // Object, not whatever ValueEditors happens to have registered. Anything outside it is
        // still declarable by class name, which the tests above cover.
        assertEquals(
                List.of(String.class, Integer.class, Float.class, Double.class,
                        Long.class, Boolean.class, Object.class),
                ModuleDataBoundaryNode.CORE_TYPES);
    }

    private static List<ModuleDataBoundaryNode> freshPair() {
        return List.of(new ModuleInputNode(), new ModuleOutputNode());
    }

    private static ModuleDataBoundaryNode fresh(Class<? extends ModuleDataBoundaryNode> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("every node type needs a no-arg constructor", e);
        }
    }
}
