package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Add Module… picker offers, and in what order. */
class ModuleChoicesTest {

    private static final ModuleInterface DOORBELL = new ModuleInterface(List.of(
            new ModulePort("Loudness", ModulePort.Kind.DATA, ModulePort.Direction.IN, Float.class.getName()),
            new ModulePort("Heard", ModulePort.Kind.DATA, ModulePort.Direction.OUT, Boolean.class.getName()),
            new ModulePort("Ring", ModulePort.Kind.FLOW, ModulePort.Direction.IN, ""),
            new ModulePort("Done", ModulePort.Kind.FLOW, ModulePort.Direction.OUT, "")), List.of());

    @Test
    void everyKnownModuleIsOfferedByName() {
        List<ModuleChoices.Choice> offered = ModuleChoices.offer(
                List.of(module("b", "Porch"), module("a", "Doorbell")), null);

        assertEquals(List.of("Doorbell", "Porch"), offered.stream().map(ModuleChoices.Choice::name).toList());
    }

    @Test
    void theGraphBeingEditedIsNotOfferedAsAModuleOfItself() {
        List<ModuleChoices.Choice> offered = ModuleChoices.offer(
                List.of(module("a", "Doorbell"), module("b", "Porch")), "a");

        assertEquals(List.of("b"), offered.stream().map(ModuleChoices.Choice::id).toList());
    }

    @Test
    void anOpenGraphThatIsNotAModuleExcludesNothing() {
        assertEquals(2, ModuleChoices.offer(List.of(module("a", "Doorbell"), module("b", "Porch")), "").size());
        assertEquals(2, ModuleChoices.offer(List.of(module("a", "Doorbell"), module("b", "Porch")), null).size());
    }

    @Test
    void aRowSummarisesTheInterfaceItWouldGiveTheNode() {
        ModuleChoices.Choice choice = ModuleChoices.offer(List.of(module("a", "Doorbell")), null).get(0);

        assertEquals("1 in, 1 out · 1 entry, 1 exit", choice.summary());
        assertTrue(choice.isUsable());
        assertTrue(choice.needs().isEmpty());
    }

    @Test
    void aModuleWithNoPortsAtAllStillReads() {
        ModuleChoices.Choice choice = ModuleChoices.offer(
                List.of(new ModuleEntry("a", "Empty", "/m/a.json", ModuleInterface.EMPTY, List.of())), null).get(0);

        assertEquals("no ports", choice.summary());
    }

    @Test
    void aModuleNothingCanBindToIsOfferedWithTheReasonAttached() {
        ModuleEntry broken = new ModuleEntry("a", "Confused", "/m/a.json",
                new ModuleInterface(List.of(), List.of("Two Module Exit markers are both named \"Done\"")),
                List.of());

        ModuleChoices.Choice choice = ModuleChoices.offer(List.of(broken), null).get(0);

        assertFalse(choice.isUsable(), "hiding it would leave its author wondering where it went");
        assertEquals(1, choice.problems().size());
    }

    @Test
    void theLibrariesAModuleNeedsTravelWithTheRow() {
        ModuleEntry needsDiscord = new ModuleEntry("a", "Doorbell", "/m/a.json", DOORBELL,
                List.of(new GraphDependencyCheck.RequiredPlugin("housegraph-discord", "Discord", "0.3.1", null)));

        assertEquals(List.of("Discord 0.3.1"), ModuleChoices.offer(List.of(needsDiscord), null).get(0).needs());
    }

    private static ModuleEntry module(String id, String name) {
        return new ModuleEntry(id, name, "/modules/" + id + ".json", DOORBELL, List.of());
    }
}
