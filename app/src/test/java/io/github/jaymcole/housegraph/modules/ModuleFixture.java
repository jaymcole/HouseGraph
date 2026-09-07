package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleBoundaryNode;
import io.github.jaymcole.housegraph.saveformat.ClipboardDataEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardFlowEdge;
import io.github.jaymcole.housegraph.saveformat.ClipboardNode;
import io.github.jaymcole.housegraph.saveformat.GraphSnapshot;
import io.github.jaymcole.housegraph.saveformat.SaveFileFixture;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds real module save files, so no test has to hand-write JSON that would drift from the format. */
public final class ModuleFixture {

    public static final NodeRegistry REGISTRY =
            new NodeRegistry(List.of(NodeRegistry.ScanRoot.core(ModuleFixture.class.getClassLoader())));

    private ModuleFixture() {
    }

    /** A boundary marker with its name already declared, ready to drop into a fixture graph. */
    public static <T extends ModuleBoundaryNode> T named(T marker, String name) {
        marker.setDeclaredName(name);
        return marker;
    }

    /** The save file a graph of these nodes would produce, with no module identity yet. */
    static JSONObject graphOf(BaseNode... nodes) {
        return builder(REGISTRY).with(nodes).build();
    }

    /** The same, already carrying the module id {@code id}. */
    static JSONObject moduleOf(String id, BaseNode... nodes) {
        return builder(REGISTRY).with(nodes).build(id);
    }

    /**
     * A builder for a module with wiring inside it, which is what an executable module needs and the
     * varargs forms above cannot express.
     *
     * @param registry the registry the file is written against; it must be the same one the module
     *                 is later read back with, or a node built from a package it does not scan comes
     *                 back as a placeholder
     * @return a fresh builder
     */
    public static Builder builder(NodeRegistry registry) {
        return new Builder(registry);
    }

    /**
     * Assembles one module file, resolving edge endpoints by node <em>identity</em> rather than by
     * the indices the save format stores — the indices are then derived here, which is the whole
     * point: a test says what it means to wire and never counts positions.
     */
    public static final class Builder {

        private final NodeRegistry registry;
        private final List<BaseNode> nodes = new ArrayList<>();
        private final List<ClipboardDataEdge> dataEdges = new ArrayList<>();
        private final List<ClipboardFlowEdge> flowEdges = new ArrayList<>();

        private Builder(NodeRegistry registry) {
            this.registry = registry;
        }

        /** Adds nodes, in the order the file will list them — which is the order a module's ports come out in. */
        public Builder with(BaseNode... added) {
            for (BaseNode node : added) {
                nodes.add(node);
            }
            return this;
        }

        /** Wires one data edge: {@code source}'s output at {@code sourcePort} into {@code target}'s input at {@code targetPort}. */
        public Builder data(BaseNode source, int sourcePort, BaseNode target, int targetPort) {
            dataEdges.add(new ClipboardDataEdge(indexOf(source), sourcePort, indexOf(target), targetPort, List.of()));
            return this;
        }

        /** Wires one flow edge, by the same convention. */
        public Builder flow(BaseNode source, int sourcePort, BaseNode target, int targetPort) {
            flowEdges.add(new ClipboardFlowEdge(indexOf(source), sourcePort, indexOf(target), targetPort, List.of()));
            return this;
        }

        /** The save file, with no module identity. */
        public JSONObject build() {
            List<ClipboardNode> entries = new ArrayList<>();
            for (BaseNode node : nodes) {
                entries.add(new ClipboardNode(node, 0.0, 0.0));
            }
            return SaveFileFixture.toJson(new GraphSnapshot(entries, dataEdges, flowEdges), registry);
        }

        /** The same, carrying the module id {@code id}. */
        public JSONObject build(String id) {
            JSONObject root = build();
            root.put(ModuleFile.MODULE_KEY, new JSONObject().put("id", id));
            return root;
        }

        /** Writes the module into {@code directory} as {@code <id>.json}, where a {@link ModuleLibrary} will find it. */
        public Path writeTo(Path directory, String id) throws IOException {
            Path file = directory.resolve(id + ".json");
            Files.writeString(file, build(id).toString(2), StandardCharsets.UTF_8);
            return file;
        }

        private int indexOf(BaseNode node) {
            int index = nodes.indexOf(node);
            if (index < 0) {
                throw new IllegalArgumentException("Wire only nodes that were added: " + node.getName());
            }
            return index;
        }
    }
}
