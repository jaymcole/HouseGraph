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
}
