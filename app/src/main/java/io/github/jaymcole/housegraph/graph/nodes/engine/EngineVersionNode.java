package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.AppVersion;
import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;

@Display.Name("Engine Version")
@Display.Description("The version of the running HouseGraph build, or \"(development build)\" outside a release jar.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"version", "build", "release", "about", "engine", "housegraph"})
public class EngineVersionNode extends BaseNode {

    private final NodeVariable<String> version = new NodeVariable<>("Version", String.class);

    @Override
    public void process(ProcessContext ctx) {
        version.setValue(AppVersion.describe());
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(version);
    }
}
