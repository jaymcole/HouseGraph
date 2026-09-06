package io.github.jaymcole.housegraph.headless.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.AutoStartable;

import java.util.Map;

/**
 * Stands in for a node from a library that has not adopted the viewless lifecycle: it keeps its
 * status in a control that only {@code createNodeContent()} builds, so resuming it headlessly
 * dereferences null.
 *
 * <p>Throwing an {@link NullPointerException} directly rather than actually holding a
 * {@code javafx.scene.control.Label} keeps the fixture from needing a toolkit to construct — the
 * failure the runner has to survive is the throw, not which field produced it.
 */
@Display.Name("Unadopted Library")
public class UnadoptedLibraryNode extends BaseNode implements AutoStartable {

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void loadState(Map<String, String> state) {
    }

    @Override
    public void autoStartIfWasRunning() {
        throw new NullPointerException(
                "Cannot invoke \"javafx.scene.control.Label.setText(String)\" because \"this.statusLabel\" is null");
    }
}
