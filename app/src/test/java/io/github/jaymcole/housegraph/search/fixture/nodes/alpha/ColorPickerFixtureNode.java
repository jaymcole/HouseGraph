package io.github.jaymcole.housegraph.search.fixture.nodes.alpha;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;

/** A name long enough that a misspelling of it still has to be found. */
@Display.Name("Color Picker")
@Display.Description("Chooses a colour from a palette.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"palette", "swatch"})
public class ColorPickerFixtureNode extends BaseNode {

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
