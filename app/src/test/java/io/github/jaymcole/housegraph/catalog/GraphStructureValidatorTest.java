package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStructureValidatorTest {

    private static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(GraphStructureValidatorTest.class.getClassLoader())));

    private static JSONObject node(String type) {
        return new JSONObject().put("type", type).put("x", 0).put("y", 0);
    }

    private static JSONObject dataEdge(int sourceNode, Object sourceVariable, int targetNode, Object targetVariable) {
        return new JSONObject()
                .put("sourceNode", sourceNode).put("sourceVariable", sourceVariable)
                .put("targetNode", targetNode).put("targetVariable", targetVariable);
    }

    private static JSONObject flowEdge(int sourceNode, Object sourcePort, int targetNode, Object targetPort) {
        return new JSONObject()
                .put("sourceNode", sourceNode).put("sourcePort", sourcePort)
                .put("targetNode", targetNode).put("targetPort", targetPort);
    }

    private static JSONObject root(JSONArray nodes, JSONArray dataEdges, JSONArray flowEdges) {
        return new JSONObject().put("version", 2).put("nodes", nodes)
                .put("dataEdges", dataEdges).put("flowEdges", flowEdges);
    }

    @Test
    void anEmptyGraphIsValid() {
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(new JSONArray(), new JSONArray(), new JSONArray()), REGISTRY);

        assertTrue(report.isValid());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    void aWellFormedGraphIsValid() {
        JSONArray nodes = new JSONArray(List.of(node("ConstantIntegerNode"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(0, "out", 1, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }

    @Test
    void reportsADataEdgeSourceNodeThatDoesNotExist() {
        JSONArray nodes = new JSONArray(List.of(node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(5, "Sum", 0, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertEquals(1, report.findings().size());
        GraphStructureValidator.Finding finding = report.findings().get(0);
        assertEquals(GraphStructureValidator.Codes.DANGLING_NODE_REFERENCE, finding.code());
        assertEquals("/dataEdges/0/sourceNode", finding.pointer());
    }

    @Test
    void reportsAFlowEdgeTargetNodeThatDoesNotExist() {
        JSONArray nodes = new JSONArray(List.of(node("AddNode")));
        JSONArray flowEdges = new JSONArray(List.of(flowEdge(0, "", 9, "")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, new JSONArray(), flowEdges), REGISTRY);

        assertEquals(1, report.findings().size());
        assertEquals(GraphStructureValidator.Codes.DANGLING_NODE_REFERENCE, report.findings().get(0).code());
        assertEquals("/flowEdges/0/targetNode", report.findings().get(0).pointer());
    }

    @Test
    void reportsADataVariableThatDoesNotExistOnTheNode() {
        JSONArray nodes = new JSONArray(List.of(node("ConstantIntegerNode"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(0, "out", 1, "NoSuchInput")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertEquals(1, report.findings().size());
        GraphStructureValidator.Finding finding = report.findings().get(0);
        assertEquals(GraphStructureValidator.Codes.UNRESOLVED_PORT, finding.code());
        assertEquals("/dataEdges/0/targetVariable", finding.pointer());
    }

    @Test
    void reportsAFlowPortThatDoesNotExistOnTheNode() {
        JSONArray nodes = new JSONArray(List.of(node("AddNode"), node("AddNode")));
        JSONArray flowEdges = new JSONArray(List.of(flowEdge(0, "NoSuchPort", 1, "")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, new JSONArray(), flowEdges), REGISTRY);

        assertEquals(1, report.findings().size());
        GraphStructureValidator.Finding finding = report.findings().get(0);
        assertEquals(GraphStructureValidator.Codes.UNRESOLVED_PORT, finding.code());
        assertEquals("/flowEdges/0/sourcePort", finding.pointer());
    }

    @Test
    void reportsAnIncompatibleDataTypeConnection() {
        // A String output has no path to a Boolean input: not assignable, and no converter is
        // registered for that pair (see TypeConverters).
        JSONArray nodes = new JSONArray(List.of(node("ConstantStringNode"), node("IfBoolNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(0, "out", 1, "Condition")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertEquals(1, report.findings().size());
        assertEquals(GraphStructureValidator.Codes.TYPE_MISMATCH, report.findings().get(0).code());
        assertEquals("/dataEdges/0", report.findings().get(0).pointer());
    }

    @Test
    void allowsACompatibleConversionBetweenNumericTypes() {
        // Integer -> Float is a registered SAFE conversion, not a mismatch.
        JSONArray nodes = new JSONArray(List.of(node("ConstantIntegerNode"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(0, "out", 1, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertTrue(report.isValid());
    }

    @Test
    void reportsTwoDataEdgesFeedingTheSameInput() {
        JSONArray nodes = new JSONArray(List.of(node("ConstantIntegerNode"), node("ConstantIntegerNode"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(
                dataEdge(0, "out", 2, "V1"),
                dataEdge(1, "out", 2, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertEquals(1, report.findings().size());
        GraphStructureValidator.Finding finding = report.findings().get(0);
        assertEquals(GraphStructureValidator.Codes.DUPLICATE_INPUT_EDGE, finding.code());
        assertEquals("/dataEdges/1", finding.pointer());
        assertEquals(List.of("/dataEdges/0"), finding.relatedPointers());
    }

    @Test
    void reportsATwoNodeDataCycle() {
        JSONArray nodes = new JSONArray(List.of(node("AddNode"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(
                dataEdge(0, "Sum", 1, "V1"),
                dataEdge(1, "Sum", 0, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        List<GraphStructureValidator.Finding> cycles = report.findings().stream()
                .filter(f -> f.code().equals(GraphStructureValidator.Codes.DATA_CYCLE))
                .toList();
        assertEquals(1, cycles.size());
        assertEquals(2, cycles.get(0).relatedPointers().size() + 1);
    }

    @Test
    void doesNotFlagAFlowCycleAsAnError() {
        // Flow may legally loop back - flowVisited dedup makes it a well-defined no-op, not a defect.
        JSONArray nodes = new JSONArray(List.of(node("IfBoolNode"), node("AddNode")));
        JSONArray flowEdges = new JSONArray(List.of(
                flowEdge(0, "True", 1, ""),
                flowEdge(1, "", 0, "")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, new JSONArray(), flowEdges), REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }

    @Test
    void skipsTypeAndPortChecksForAnUnresolvableNodeType() {
        // GraphDependencyCheck's job is reporting a missing library, not this one's.
        JSONArray nodes = new JSONArray(List.of(node("NoSuchNodeType"), node("AddNode")));
        JSONArray dataEdges = new JSONArray(List.of(dataEdge(0, "anything", 1, "V1")));
        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(root(nodes, dataEdges, new JSONArray()), REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }

    // --- Modules --------------------------------------------------------------------------------

    /** A graph carrying a module identity of its own, plus a table of the modules it references. */
    private static JSONObject moduleRoot(String ownId, JSONArray nodes, String... references) {
        JSONObject root = root(nodes, new JSONArray(), new JSONArray());
        root.put("version", 3);
        if (ownId != null) {
            root.put("module", new JSONObject().put("id", ownId));
        }
        if (references.length > 0) {
            JSONArray modules = new JSONArray();
            for (String reference : references) {
                modules.put(new JSONObject().put("id", reference));
            }
            root.put("modules", modules);
        }
        return root;
    }

    private static List<GraphStructureValidator.Finding> findings(GraphStructureValidator.Report report, String code) {
        return report.findings().stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    void aModuleReferencingItselfIsReportedWithNoResolverAtAll() {
        JSONObject root = moduleRoot("m-1", new JSONArray(List.of(node("AddNode"))), "m-1");

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(root, REGISTRY);

        List<GraphStructureValidator.Finding> cycles =
                findings(report, GraphStructureValidator.Codes.MODULE_CYCLE);
        assertEquals(1, cycles.size());
        assertEquals("/modules/0", cycles.get(0).pointer());
    }

    @Test
    void aCycleThroughAnotherModuleIsReportedRatherThanStackOverflowed() {
        // A references B, B references A. Walking it without cycle detection would recurse forever.
        JSONObject a = moduleRoot("m-a", new JSONArray(List.of(node("AddNode"))), "m-b");
        JSONObject b = moduleRoot("m-b", new JSONArray(List.of(node("AddNode"))), "m-a");

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(a, REGISTRY,
                id -> "m-b".equals(id) ? b : null);

        List<GraphStructureValidator.Finding> cycles =
                findings(report, GraphStructureValidator.Codes.MODULE_CYCLE);
        assertEquals(1, cycles.size());
        assertTrue(cycles.get(0).message().contains("m-a"), cycles.get(0).message());
        assertTrue(cycles.get(0).message().contains("m-b"), cycles.get(0).message());
    }

    @Test
    void aLongerCycleTerminatesInsteadOfRecursing() {
        // A -> B -> C -> B. The repeat is not the graph being validated, so only the on-path check
        // catches it; without one, the walk never returns.
        JSONObject a = moduleRoot("m-a", new JSONArray(List.of(node("AddNode"))), "m-b");
        JSONObject b = moduleRoot("m-b", new JSONArray(List.of(node("AddNode"))), "m-c");
        JSONObject c = moduleRoot("m-c", new JSONArray(List.of(node("AddNode"))), "m-b");

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(a, REGISTRY,
                id -> switch (id) {
                    case "m-b" -> b;
                    case "m-c" -> c;
                    default -> null;
                });

        assertEquals(1, findings(report, GraphStructureValidator.Codes.MODULE_CYCLE).size());
    }

    @Test
    void anAcyclicModuleReferenceIsFine() {
        JSONObject a = moduleRoot("m-a", new JSONArray(List.of(node("AddNode"))), "m-b");
        JSONObject b = moduleRoot("m-b", new JSONArray(List.of(node("AddNode"))));

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(a, REGISTRY,
                id -> "m-b".equals(id) ? b : null);

        assertTrue(report.isValid(), report.findings().toString());
    }

    @Test
    void anUnresolvableModuleReferenceIsNotGuessedAtAsACycle() {
        JSONObject a = moduleRoot("m-a", new JSONArray(List.of(node("AddNode"))), "m-gone");

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(a, REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }

    @Test
    void aBoundaryMarkerNothingCouldBindToIsReportedAgainstTheFileThatDeclaresIt() {
        // Two Module Inputs, neither named: one blank-name problem each.
        JSONArray nodes = new JSONArray(List.of(node("ModuleInputNode"), node("ModuleInputNode")));
        JSONObject root = moduleRoot(null, nodes);

        GraphStructureValidator.Report report = GraphStructureValidator.inspect(root, REGISTRY);

        List<GraphStructureValidator.Finding> conflicts =
                findings(report, GraphStructureValidator.Codes.MODULE_BOUNDARY_CONFLICT);
        assertEquals(2, conflicts.size());
        assertEquals("/nodes/0", conflicts.get(0).pointer());
        assertEquals(List.of("/nodes/1"), conflicts.get(0).relatedPointers());
    }

    @Test
    void aSoundlyDeclaredModuleReportsNothing() {
        JSONArray nodes = new JSONArray(List.of(
                node("ModuleInputNode").put("state", new JSONObject().put("name", "A")),
                node("ModuleExitNode").put("state", new JSONObject().put("name", "Done"))));

        GraphStructureValidator.Report report =
                GraphStructureValidator.inspect(moduleRoot(null, nodes), REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }

    @Test
    void anOrdinaryGraphIsNeverCheckedForBoundaryConflicts() {
        GraphStructureValidator.Report report = GraphStructureValidator.inspect(
                root(new JSONArray(List.of(node("AddNode"))), new JSONArray(), new JSONArray()), REGISTRY);

        assertTrue(report.isValid(), report.findings().toString());
    }
}
