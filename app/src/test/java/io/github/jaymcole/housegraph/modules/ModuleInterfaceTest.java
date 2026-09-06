package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.jaymcole.housegraph.modules.ModuleFixture.REGISTRY;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.graphOf;
import static io.github.jaymcole.housegraph.modules.ModuleFixture.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a graph's boundary markers become the face it presents to a consumer — above all that
 * <b>every direction inverts</b>, since a test that agreed with a backwards implementation would be
 * worse than no test.
 */
class ModuleInterfaceTest {

    @Test
    void aModuleInputBecomesADataInPortEvenThoughItsOwnPortIsAnOutput() {
        ModuleInputNode marker = named(new ModuleInputNode(), "Temperature");
        marker.setDeclaredType(Float.class);

        // The marker's own port, read from inside the module, is an OUTPUT.
        assertEquals(1, marker.getOutputs().size());
        assertTrue(marker.getInputs().isEmpty());

        ModulePort port = only(ModuleInterface.derive(graphOf(marker), REGISTRY));

        // Read from outside, it inverts: the consumer feeds this port.
        assertEquals("Temperature", port.name());
        assertEquals(ModulePort.Kind.DATA, port.kind());
        assertEquals(ModulePort.Direction.IN, port.direction());
        assertEquals(Float.class.getName(), port.typeName());
    }

    @Test
    void aModuleOutputBecomesADataOutPortEvenThoughItsOwnPortIsAnInput() {
        ModuleOutputNode marker = named(new ModuleOutputNode(), "Verdict");
        marker.setDeclaredType(Boolean.class);

        assertEquals(1, marker.getInputs().size());
        assertTrue(marker.getOutputs().isEmpty());

        ModulePort port = only(ModuleInterface.derive(graphOf(marker), REGISTRY));

        assertEquals(ModulePort.Kind.DATA, port.kind());
        assertEquals(ModulePort.Direction.OUT, port.direction());
        assertEquals(Boolean.class.getName(), port.typeName());
    }

    @Test
    void aModuleEntryBecomesAFlowInPortEvenThoughItsOwnPortIsAFlowOutput() {
        ModuleEntryNode marker = named(new ModuleEntryNode(), "Start");

        assertEquals(1, marker.getFlowOutputs().size());
        assertTrue(marker.getFlowInputs().isEmpty());

        ModulePort port = only(ModuleInterface.derive(graphOf(marker), REGISTRY));

        assertEquals("Start", port.name());
        assertEquals(ModulePort.Kind.FLOW, port.kind());
        assertEquals(ModulePort.Direction.IN, port.direction());
        assertEquals("", port.typeName(), "a flow port carries no value, so it declares no type");
    }

    @Test
    void aModuleExitBecomesAFlowOutPortEvenThoughItsOwnPortIsAFlowInput() {
        ModuleExitNode marker = named(new ModuleExitNode(), "Done");

        assertEquals(1, marker.getFlowInputs().size());
        assertTrue(marker.getFlowOutputs().isEmpty());

        ModulePort port = only(ModuleInterface.derive(graphOf(marker), REGISTRY));

        assertEquals(ModulePort.Kind.FLOW, port.kind());
        assertEquals(ModulePort.Direction.OUT, port.direction());
    }

    @Test
    void portsComeBackInTheFilesNodeOrderAndIgnoreEverythingThatIsNotAMarker() {
        JSONObject root = graphOf(
                named(new ModuleEntryNode(), "Start"),
                new AddNode(),
                named(new ModuleInputNode(), "A"),
                named(new ModuleExitNode(), "Done"));

        ModuleInterface derived = ModuleInterface.derive(root, REGISTRY);

        assertEquals(List.of("Start", "A", "Done"), derived.ports().stream().map(ModulePort::name).toList());
        assertTrue(derived.isSound());
    }

    @Test
    void aDeclaredTypeThisMachineCannotLoadIsCarriedAcrossUnchanged() {
        ModuleInputNode marker = named(new ModuleInputNode(), "Reading");
        marker.setDeclaredType("com.example.NotInstalled");

        ModulePort port = only(ModuleInterface.derive(graphOf(marker), REGISTRY));

        assertEquals("com.example.NotInstalled", port.typeName(),
                "the declaration is recorded, not the Object the port degraded to");
    }

    @Test
    void aMarkerWithNoNameMakesTheInterfaceUnbindableButStillProducesItsPort() {
        ModuleInterface derived = ModuleInterface.derive(graphOf(new ModuleInputNode()), REGISTRY);

        assertFalse(derived.isSound());
        assertEquals(1, derived.problems().size());
        assertTrue(derived.problems().get(0).startsWith("Module Input"), derived.problems().get(0));
        assertEquals(1, derived.ports().size(), "the port is still reported, so a consumer keeps it");
    }

    @Test
    void twoMarkersSharingANameOnTheSameSideCollide() {
        ModuleInterface derived = ModuleInterface.derive(graphOf(
                named(new ModuleInputNode(), "Value"),
                named(new ModuleInputNode(), "Value")), REGISTRY);

        assertFalse(derived.isSound());
        assertTrue(derived.problems().get(0).contains("Value"));
    }

    @Test
    void aNameSharedAcrossDifferentSidesIsNotACollision() {
        // An edge endpoint resolves against one list - data inputs, or flow inputs - so a data port
        // and a flow port may both be called "Value" without either becoming ambiguous.
        ModuleInterface derived = ModuleInterface.derive(graphOf(
                named(new ModuleInputNode(), "Value"),
                named(new ModuleOutputNode(), "Value"),
                named(new ModuleEntryNode(), "Value"),
                named(new ModuleExitNode(), "Value")), REGISTRY);

        assertTrue(derived.isSound(), () -> String.join("; ", derived.problems()));
        assertEquals(4, derived.ports().size());
    }

    @Test
    void aGraphWithNoMarkersDeclaresAnEmptyInterface() {
        ModuleInterface derived = ModuleInterface.derive(graphOf(new AddNode()), REGISTRY);

        assertEquals(List.of(), derived.ports());
        assertTrue(derived.isSound());
    }

    @Test
    void aPortListSurvivesEncodingAndDecoding() {
        List<ModulePort> ports = List.of(
                new ModulePort("Temp, in °C", ModulePort.Kind.DATA, ModulePort.Direction.IN, Float.class.getName()),
                new ModulePort("Done: really", ModulePort.Kind.FLOW, ModulePort.Direction.OUT, ""));

        // Names are stored verbatim, so the encoding has to survive the commas and colons a user can
        // type into one - which a delimiter-joined form would not.
        assertEquals(ports, ModulePort.decode(ModulePort.encode(ports)));
    }

    @Test
    void aMalformedEncodingCostsOnlyThePortsItCannotRead() {
        assertEquals(List.of(), ModulePort.decode("not json at all"));
        assertEquals(List.of(), ModulePort.decode(null));
        assertEquals(1, ModulePort.decode("[[\"data\",\"in\",\"A\",\"\"],[\"nonsense\",\"in\",\"B\",\"\"]]").size());
    }

    private static ModulePort only(ModuleInterface derived) {
        assertEquals(1, derived.ports().size());
        return derived.ports().get(0);
    }
}
