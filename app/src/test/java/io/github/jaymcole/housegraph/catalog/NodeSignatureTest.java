package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.nodes.constants.ConstantFloatNode;
import io.github.jaymcole.housegraph.graph.nodes.math.AddNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSignatureTest {

    @Test
    void isStableAcrossTwoFreshInstancesOfTheSameType() {
        String first = NodeSignature.of(NodeKind.DATA, new AddNode());
        String second = NodeSignature.of(NodeKind.DATA, new AddNode());

        assertEquals(first, second);
    }

    @Test
    void looksLikeASixteenCharacterHexFingerprint() {
        String signature = NodeSignature.of(NodeKind.DATA, new AddNode());

        assertTrue(signature.matches("^[0-9a-f]{16}$"), signature);
    }

    @Test
    void differsBetweenTwoDifferentlyShapedNodeTypes() {
        String add = NodeSignature.of(NodeKind.DATA, new AddNode());
        String constant = NodeSignature.of(NodeKind.DATA, new ConstantFloatNode());

        assertNotEquals(add, constant);
    }

    @Test
    void changesWhenAnInputBecomesRequired() {
        AddNode unmodified = new AddNode();
        String before = NodeSignature.of(NodeKind.DATA, unmodified);

        AddNode modified = new AddNode();
        modified.getInputs().get(0).setRequired(true);
        String after = NodeSignature.of(NodeKind.DATA, modified);

        assertNotEquals(before, after, "a required flag is part of what a save file binds against");
    }

    @Test
    void changesWhenTheDeclaredKindChanges() {
        AddNode node = new AddNode();

        assertNotEquals(
                NodeSignature.of(NodeKind.DATA, node),
                NodeSignature.of(NodeKind.ACTION, node));
    }
}
