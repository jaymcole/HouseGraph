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
}
