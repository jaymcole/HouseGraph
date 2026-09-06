package io.github.jaymcole.housegraph.graph.nodes.module.fixture;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publishes itself under one fixed {@link ResourceRegistry} name from {@code onActivated()}, exactly
 * as the resource-node contract says to — which is what makes it the thing to put inside a module
 * when the point is that registry names are app-wide.
 */
@Display.Name("Resource Fixture")
public class ResourceFixtureNode extends BaseNode {

    /** The name every instance claims, which is the whole point. */
    public static final String NAME = "module-resource-fixture";

    /** Every instance that has ever registered, in registration order. */
    public static final List<ResourceFixtureNode> REGISTERED = new CopyOnWriteArrayList<>();

    @Override
    protected void onActivated() {
        REGISTERED.add(this);
        ResourceRegistry.shared().register(NAME, this);
    }

    @Override
    protected void onRemoved() {
        // Only if we are still the holder: unregistering blindly would take the displacing
        // instance's registration down with ours.
        if (ResourceRegistry.shared().find(NAME, ResourceFixtureNode.class).orElse(null) == this) {
            ResourceRegistry.shared().unregister(NAME);
        }
    }

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }
}
