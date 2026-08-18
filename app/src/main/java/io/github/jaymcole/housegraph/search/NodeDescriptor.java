package io.github.jaymcole.housegraph.search;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;

import java.util.List;
import java.util.Objects;

/**
 * One node type flattened into everything a search can match it on.
 *
 * <p>Assembled once per index build from three sources, none of which requires constructing the
 * node: {@code NodeRegistry.Entry} for what the scan found, {@code NodeMetadata} for what the
 * class declares, and the plugin catalog for the owning library's human name.
 *
 * <p><b>Ports are absent by design.</b> A node's inputs and outputs are built lazily by
 * {@code BaseNode.configureInputs()}, so reading them would mean instantiating every discovered
 * type at index build — running plugin static initializers early and making one badly behaved
 * constructor everyone's problem. See {@code docs/engine/node-search.md} for what indexing them
 * would take, if it is ever worth it.
 *
 * @param nodeClass   the node type itself, so a caller can instantiate the result
 * @param typeId      its stable save-file id, from {@code NodeRegistry.persistentTypeId}
 * @param displayName its user-facing label
 * @param simpleName  its simple class name, which often carries terms the display name drops
 * @param categoryPath its dot-separated menu category
 * @param pluginId    the owning library's id; {@code core} for built-ins
 * @param libraryName the owning library's human name, or empty for built-ins
 * @param description the {@code @Display.Description} value, or empty
 * @param keywords    the {@code @Node.Keywords} values, or empty
 * @param kind        the node's declared role, or null when untagged
 */
public record NodeDescriptor(Class<? extends BaseNode> nodeClass,
                             String typeId,
                             String displayName,
                             String simpleName,
                             String categoryPath,
                             String pluginId,
                             String libraryName,
                             String description,
                             List<String> keywords,
                             NodeKind kind) {

    public NodeDescriptor {
        Objects.requireNonNull(nodeClass, "nodeClass");
        typeId = orEmpty(typeId);
        displayName = orEmpty(displayName);
        simpleName = orEmpty(simpleName);
        categoryPath = orEmpty(categoryPath);
        pluginId = orEmpty(pluginId);
        libraryName = orEmpty(libraryName);
        description = orEmpty(description);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
