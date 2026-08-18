package io.github.jaymcole.housegraph.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Namespace for node-related marker annotations. Not applied directly; use its nested
 * members.
 */
public @interface Node {

    /**
     * Hides a {@link io.github.jaymcole.housegraph.graph.BaseNode} subclass from the
     * Add-Node menu: {@code NodeRegistry.discover()} skips a class annotated with this,
     * so it never appears as an option to add. The type stays fully loadable via
     * {@code NodeRegistry.resolveClass}, so a graph saved while the node type was enabled
     * still opens after it's been disabled. Use it for work-in-progress or deprecated
     * node types. The {@code value} is a human-readable reason, for documentation only.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Disabled {
        String value() default "Disabled";
    }

    /**
     * A stable identifier for a node type in save files, decoupled from its class name.
     * <p>
     * By default a save file identifies a node by its <em>simple class name</em> (see
     * {@code NodeRegistry.persistentTypeId}), which already survives moving the class between
     * packages/category folders — the common refactor. Declare this only when you need an id
     * that differs from the simple class name: to give a curated id, or — after <em>renaming</em>
     * the class — to keep resolving the id that older saves already contain by pinning it here
     * (or listing the old id/class name in {@link #aliases()}).
     * <p>
     * {@code NodeRegistry.resolveClass} matches a saved id against the {@code value}, the
     * {@code aliases}, and the simple class name; whichever a save stored, the node still loads.
     * Keep the {@code value} unique across node types — a collision makes both ids ambiguous and
     * falls back to fully-qualified-class-name resolution.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Type {
        /** The canonical stable id written to save files for this node type. */
        String value();

        /** Additional ids that still resolve to this type (e.g. a previous id or class name). */
        String[] aliases() default {};
    }

    /**
     * The node's semantic role, used to filter and group search results. See {@link NodeKind} for
     * the four values and why they are orthogonal to a node's category folder.
     * <p>
     * Declaring this is optional but not cosmetic: a node with no kind matches no kind-filtered
     * search, because guessing a kind from structure or folder name would be wrong often enough
     * to be worse than leaving it out. An out-of-tree library in particular has an arbitrary
     * category path, so nothing can be inferred from it.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Kind {
        /** The role this node plays. */
        NodeKind value();
    }

    /**
     * Extra terms a user might search for that the node's own name does not contain — synonyms,
     * symbols, and the words someone reaches for before they know what the node is called.
     * {@code AddNode} declaring {@code {"plus", "sum", "+"}} is the shape of it.
     * <p>
     * This is the main lever for <em>discovering</em> a node rather than re-finding one. Search
     * ranks a keyword hit just below a display-name hit, so a well-chosen keyword surfaces a node
     * to someone who could never have guessed its name.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Keywords {
        /** Search terms for this node type. */
        String[] value();
    }
}
