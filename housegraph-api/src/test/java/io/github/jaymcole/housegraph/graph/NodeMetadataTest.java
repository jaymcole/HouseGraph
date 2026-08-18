package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeMetadataTest {

    @Test
    void readsEveryDeclaredAnnotation() {
        NodeMetadata metadata = NodeMetadata.of(FullyTaggedNode.class);

        assertEquals("Adds two numbers together.", metadata.description());
        assertEquals(List.of("plus", "sum", "+"), metadata.keywords());
        assertEquals(NodeKind.DATA, metadata.kind());
        assertFalse(metadata.isEmpty());
    }

    @Test
    void anUntaggedNodeYieldsEmptyMetadataRatherThanNulls() {
        NodeMetadata metadata = NodeMetadata.of(UntaggedNode.class);

        assertEquals("", metadata.description());
        assertTrue(metadata.keywords().isEmpty());
        assertNull(metadata.kind(), "no kind was declared, and guessing one would be worse than none");
        assertTrue(metadata.isEmpty());
    }

    @Test
    void aNullClassYieldsNone() {
        assertSame(NodeMetadata.NONE, NodeMetadata.of(null));
    }

    @Test
    void implementingAutoStartableInfersResource() {
        assertEquals(NodeKind.RESOURCE, NodeMetadata.of(AutoStartableNode.class).kind(),
                "a running/stopped lifecycle is a resource even when the author declared nothing");
    }

    @Test
    void aDeclaredKindWinsOverTheAutoStartableFallback() {
        assertEquals(NodeKind.CONTROL, NodeMetadata.of(RepeatingTriggerLikeNode.class).kind(),
                "a repeating trigger implements AutoStartable too, so letting the interface win "
                        + "would misfile every one of them as a resource");
    }

    @Test
    void blankKeywordsAreDroppedAndTheRestTrimmed() {
        assertEquals(List.of("one", "two"), NodeMetadata.of(SloppyKeywordsNode.class).keywords());
    }

    @Test
    void keywordsAreDefensivelyCopied() {
        NodeMetadata metadata = NodeMetadata.of(FullyTaggedNode.class);
        assertThrows(UnsupportedOperationException.class, () -> metadata.keywords().add("nope"));
    }

    // --- fixtures ---------------------------------------------------------------

    @Display.Description("Adds two numbers together.")
    @Node.Kind(NodeKind.DATA)
    @Node.Keywords({"plus", "sum", "+"})
    private static class FullyTaggedNode extends StubNode {
    }

    private static class UntaggedNode extends StubNode {
    }

    @Node.Keywords({"one", "  ", "  two  ", ""})
    private static class SloppyKeywordsNode extends StubNode {
    }

    private static class AutoStartableNode extends StubNode implements AutoStartable {
        @Override
        public void autoStartIfWasRunning() {
        }
    }

    @Node.Kind(NodeKind.CONTROL)
    private static class RepeatingTriggerLikeNode extends StubNode implements AutoStartable {
        @Override
        public void autoStartIfWasRunning() {
        }
    }

    private abstract static class StubNode extends BaseNode {
        @Override
        public void configureInputs() {
        }

        @Override
        public void configureOutputs() {
        }

        @Override
        public void process(ProcessContext ctx) {
        }
    }
}
