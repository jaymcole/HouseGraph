package io.github.jaymcole.housegraph.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Namespace for display-related annotations. Not applied directly; use its nested members.
 */
public @interface Display {

    /**
     * The label a node type is shown under in the UI (the Add-Node menu and the node's
     * title bar). {@link io.github.jaymcole.housegraph.graph.BaseNode#getName()} and
     * {@code NodeRegistry} read it, falling back to the class's simple name when the
     * annotation is absent or its {@code value} is blank.
     */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Name {
        String value() default "";
    }

    /**
     * A one-line summary of what the node does, shown alongside its name in search results and
     * read by {@code NodeMetadata}.
     * <p>
     * Write it for someone who has not found the node yet. "Joins a list into a single string,
     * one entry per line" earns its place; "List to string node" repeats the name and does not.
     * Search weights a description hit below the name and keywords, so this is where the words a
     * user would <em>describe</em> the node with belong, rather than the words they would name
     * it with.
     */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Description {
        /** The one-line summary. */
        String value();
    }
}
