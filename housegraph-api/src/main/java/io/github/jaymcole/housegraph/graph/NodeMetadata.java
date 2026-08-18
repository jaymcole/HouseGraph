package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.sdk.AutoStartable;

import java.util.ArrayList;
import java.util.List;

/**
 * The searchable, human-facing facts a node type declares about itself: what it does, what a user
 * might call it, and what role it plays.
 *
 * <h2>Why this is separate from {@code NodeRegistry.Entry}</h2>
 * {@link NodeRegistry.Entry} carries what the <em>scan</em> discovered — the class, its category
 * folder, its owning library — facts that come from where a class was found rather than from the
 * class itself. This record carries what the class <em>declares</em>. Keeping them apart is not
 * only tidiness: {@code Entry} is a published record, and appending components to it would be a
 * binary-incompatible change forcing a rebuild of every out-of-tree node library. A separate
 * record is strictly additive, so libraries compiled against an older API keep working untouched.
 *
 * <h2>Reflection only — never instantiation</h2>
 * {@link #of} reads annotations and interfaces off the {@link Class} and never calls a
 * constructor. That is deliberate and load-bearing: {@code NodeRegistry} discovers node classes
 * with {@code Class.forName(name, false, loader)} precisely so that a library's static
 * initializers do not run at scan time, and reading metadata must not be what finally runs them.
 * It also means metadata is readable for a node type that cannot be initialized at all.
 * <p>
 * The cost of that discipline is that a node's <em>ports</em> are not visible here — they are
 * built lazily by {@code BaseNode.configureInputs()} and friends, which needs an instance. Port
 * metadata is therefore not part of the searchable surface.
 *
 * @param description the {@code @Display.Description} value, or empty when absent
 * @param keywords    the {@code @Node.Keywords} values, or empty when absent; never null
 * @param kind        the node's role, or null when the author declared none
 */
public record NodeMetadata(String description, List<String> keywords, NodeKind kind) {

    /** What a node type with no metadata annotations at all yields. */
    public static final NodeMetadata NONE = new NodeMetadata("", List.of(), null);

    public NodeMetadata {
        description = description == null ? "" : description.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * Reads the metadata a node type declares.
     * <p>
     * The kind resolves in one order, and it matters: an explicit {@code @Node.Kind} always wins,
     * and only then does implementing {@link AutoStartable} imply {@link NodeKind#RESOURCE}. That
     * interface marks a running/stopped lifecycle, which a long-lived resource has — but so does
     * a repeating trigger, which is {@link NodeKind#CONTROL}. Letting the interface win would
     * misfile every such trigger, so it is only ever a fallback for a node whose author said
     * nothing.
     * <p>
     * A node that declares nothing and implements nothing has a null kind. It is left out of
     * kind-filtered searches rather than guessed into a bucket.
     *
     * @param nodeClass the node type to read
     * @return its declared metadata; {@link #NONE} for null or a wholly untagged type
     */
    public static NodeMetadata of(Class<? extends BaseNode> nodeClass) {
        if (nodeClass == null) {
            return NONE;
        }
        return new NodeMetadata(descriptionOf(nodeClass), keywordsOf(nodeClass), kindOf(nodeClass));
    }

    /** True when this node type declared nothing a search could use. */
    public boolean isEmpty() {
        return description.isEmpty() && keywords.isEmpty() && kind == null;
    }

    private static String descriptionOf(Class<? extends BaseNode> nodeClass) {
        Display.Description description = nodeClass.getAnnotation(Display.Description.class);
        return description == null ? "" : description.value();
    }

    private static List<String> keywordsOf(Class<? extends BaseNode> nodeClass) {
        Node.Keywords keywords = nodeClass.getAnnotation(Node.Keywords.class);
        if (keywords == null) {
            return List.of();
        }
        List<String> collected = new ArrayList<>();
        for (String keyword : keywords.value()) {
            if (keyword != null && !keyword.isBlank()) {
                collected.add(keyword.trim());
            }
        }
        return collected;
    }

    private static NodeKind kindOf(Class<? extends BaseNode> nodeClass) {
        Node.Kind declared = nodeClass.getAnnotation(Node.Kind.class);
        if (declared != null) {
            return declared.value();
        }
        if (AutoStartable.class.isAssignableFrom(nodeClass)) {
            return NodeKind.RESOURCE;
        }
        return null;
    }
}
