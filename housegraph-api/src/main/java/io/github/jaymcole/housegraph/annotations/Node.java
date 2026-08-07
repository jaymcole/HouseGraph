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
}
