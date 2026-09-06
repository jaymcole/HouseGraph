package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.graphOf;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.moduleOf;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a parsed root says about itself as a module, read with no file opened and no class loaded. */
class ModuleFileTest {

    @Test
    void aGraphWithABoundaryMarkerIsAModuleEvenBeforeItHasAnId() {
        assertTrue(ModuleFile.isModule(graphOf(named(new ModuleEntryNode(), "Start"))));
        assertNull(ModuleFile.idOf(graphOf(named(new ModuleEntryNode(), "Start"))));
    }

    @Test
    void anOrdinaryGraphIsNotAModule() {
        assertFalse(ModuleFile.isModule(graphOf(new AddNode())));
    }

    @Test
    void anIdIsMintedOnlyOnceAndOnlyWhenThereIsNone() {
        JSONObject root = graphOf(named(new ModuleEntryNode(), "Start"));

        String first = ModuleFile.ensureId(root);
        String second = ModuleFile.ensureId(root);

        assertEquals(first, second);
        assertEquals(first, ModuleFile.idOf(root));
        assertNotEquals(first, ModuleFile.ensureId(graphOf(named(new ModuleEntryNode(), "Start"))),
                "two graphs that look identical are still two different modules");
    }

    @Test
    void anIdentityIsCarriedOntoTheRootThatReplacesIt() {
        JSONObject onDisk = moduleOf("m-1", named(new ModuleEntryNode(), "Start"));
        ModuleFile.setName(onDisk, "Doorbell");
        JSONObject rewritten = graphOf(named(new ModuleEntryNode(), "Start"));

        ModuleFile.carryIdentity(onDisk, rewritten);

        assertEquals("m-1", ModuleFile.idOf(rewritten));
        assertEquals("Doorbell", ModuleFile.nameOf(rewritten));
    }

    @Test
    void carryingFromNothingLeavesTheNewRootWithNoIdentity() {
        JSONObject rewritten = graphOf(named(new ModuleEntryNode(), "Start"));

        ModuleFile.carryIdentity(null, rewritten);

        assertNull(ModuleFile.idOf(rewritten), "a Save As to a new path writes a new graph, not the same module");
    }

    @Test
    void aCarriedIdentityIsACopyTheOriginalCannotChangeUnderneath() {
        JSONObject onDisk = moduleOf("m-1", named(new ModuleEntryNode(), "Start"));
        JSONObject rewritten = graphOf(named(new ModuleEntryNode(), "Start"));
        ModuleFile.carryIdentity(onDisk, rewritten);

        onDisk.getJSONObject(ModuleFile.MODULE_KEY).put("id", "tampered");

        assertEquals("m-1", ModuleFile.idOf(rewritten));
    }

    @Test
    void referencedIdsComeFromTheTableInOrderWithDuplicatesDropped() {
        JSONObject root = graphOf(new AddNode());
        root.put(ModuleFile.MODULES_KEY, new JSONArray()
                .put(new JSONObject().put("id", "m-a"))
                .put(new JSONObject().put("id", "m-b"))
                .put(new JSONObject().put("id", "m-a")));

        assertEquals(List.of("m-a", "m-b"), ModuleFile.referencedIds(root));
        assertEquals(List.of("m-a", "m-b", "m-a"), ModuleFile.rowIds(root),
                "row order is kept intact so an index still maps back to a JSON Pointer");
    }
}
