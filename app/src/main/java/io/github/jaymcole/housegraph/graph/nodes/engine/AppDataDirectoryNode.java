package io.github.jaymcole.housegraph.graph.nodes.engine;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.storage.AppDirectories;

@Display.Name("App Data Directory")
@Display.Description("The root directory HouseGraph keeps its own files under on this machine (secrets, saves, plugins, logs, ...).")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"appdata", "directory", "folder", "path", "storage", "engine", "housegraph"})
public class AppDataDirectoryNode extends BaseNode {

    private final NodeVariable<String> directory = new NodeVariable<>("Directory", String.class);

    @Override
    public void process(ProcessContext ctx) {
        directory.setValue(AppDirectories.get().root().toString());
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(directory);
    }
}
