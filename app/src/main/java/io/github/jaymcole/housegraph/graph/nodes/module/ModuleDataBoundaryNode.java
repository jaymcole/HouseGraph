package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data half of the boundary set: a marker that declares a name <em>and a type</em>, and
 * carries one data port of that type.
 *
 * <h2>How a type is declared</h2>
 * As a fully-qualified class name, persisted as text and resolved to a {@link Class} when the
 * port is built. The inline chooser offers {@link #CORE_TYPES} by their simple names and accepts
 * any other fully-qualified name typed into it.
 * <p>
 * The stored text is the declaration; the resolved class is only a reading of it. That
 * distinction is what keeps a graph portable: a type provided by a plugin resolves on a machine
 * with that plugin installed and not on one without, and the machine without must not quietly
 * rewrite the declaration to something it does happen to understand. So an unresolvable name
 * <b>degrades</b> — the port is built as {@code Object} and a warning is logged — while
 * {@link #saveState()} still writes back the name exactly as it was read. Open the same graph
 * where the plugin is installed and the declaration is intact.
 *
 * <h2>Changing the type rebuilds the port</h2>
 * A {@link NodeVariable}'s name and type are final, so the port is discarded and recreated
 * through {@link #rebuildPorts()}. That churn removes and re-adds edges and pushes a new value
 * into the chooser, either of which can call back in here — so {@link #setDeclaredType(String)}
 * returns early both when the type is unchanged and when a rebuild is already in progress.
 * <p>
 * The port's <em>name</em> is fixed ({@code "Value"}) and is not the declared name. Naming the
 * port after the declaration would mean renaming a module's interface silently unbound the wiring
 * inside it, because save files bind endpoints by port name.
 */
public abstract class ModuleDataBoundaryNode extends ModuleBoundaryNode {

    private static final Logger log = Log.get(ModuleDataBoundaryNode.class);

    /**
     * The types offered in the chooser: everything {@code TypeConverters} bridges between, plus
     * {@code Object} as the escape hatch. Deliberately not derived from {@code ValueEditors},
     * which answers a narrower question — which types can be <em>typed into</em> a field — and
     * registers only three by default. Any other type is still declarable by its fully-qualified
     * name; this list is the shortcut, not the limit.
     */
    public static final List<Class<?>> CORE_TYPES = List.of(
            String.class, Integer.class, Float.class, Double.class,
            Long.class, Boolean.class, Object.class);

    /** The key {@link #saveState()} writes the declared type's class name under. */
    private static final String TYPE_KEY = "type";

    /** The fixed name of the single data port, stable across renames of the declaration. */
    static final String PORT_NAME = "Value";

    private static final String DEFAULT_TYPE_NAME = String.class.getName();

    /** The declared type's class name, never null or blank, stored exactly as it will be saved. */
    private volatile String declaredTypeName = DEFAULT_TYPE_NAME;

    /** {@link #declaredTypeName} resolved, or {@code Object} when it does not resolve here. */
    private volatile Class<?> portType = String.class;

    /** Guards against reacting to the edge and control churn our own {@link #rebuildPorts()} causes. */
    private boolean rebuilding;

    /** The type chooser, or null whenever nothing is drawing this node. */
    private ComboBox<String> typeChooser;

    /**
     * This marker's single data port — the inside face of the declaration. An output on a
     * {@link ModuleInputNode} and an input on a {@link ModuleOutputNode}; see each class for why
     * that is the right way round.
     *
     * @return the one port this marker carries, never null
     */
    public abstract NodeVariable<?> getBoundaryPort();

    /**
     * The declared type's class name as it will be persisted — which is not necessarily a class
     * this JVM can load. Never blank.
     *
     * @return the declared type's fully-qualified class name
     */
    public final String getDeclaredTypeName() {
        return declaredTypeName;
    }

    /**
     * The type this marker's port is built with: the declared type where it resolves, and
     * {@code Object} where it does not. Never null, so a port can always be built.
     *
     * @return the resolved port type, or {@code Object} when the declared type is not installed
     */
    public final Class<?> getDeclaredType() {
        return portType;
    }

    /**
     * Whether the declared type resolves in this JVM. False means the port has fallen back to
     * {@code Object} while {@link #getDeclaredTypeName()} still holds what was declared.
     *
     * @return true if the declared type is installed here
     */
    public final boolean isDeclaredTypeResolved() {
        return portType.getName().equals(declaredTypeName);
    }

    /**
     * Declares a type by class, the form node and test code has in hand.
     *
     * @param type the type to declare; null falls back to the default
     */
    public final void setDeclaredType(Class<?> type) {
        setDeclaredType(type == null ? null : type.getName());
    }

    /**
     * Declares a type by class name and rebuilds the port to match. Blank or null declares the
     * default; surrounding whitespace is trimmed, since a class name never contains any and a
     * stray space would make the declaration unresolvable everywhere rather than portably.
     * <p>
     * Returns without doing anything when the type is already what is asked for — which is what
     * makes the chooser's echo of a programmatic change a no-op instead of a loop — or when a
     * rebuild started by this method is still running.
     *
     * @param typeName the fully-qualified class name to declare, or null for the default
     */
    public final void setDeclaredType(String typeName) {
        if (rebuilding) {
            return;
        }
        String updated = normalise(typeName);
        if (updated.equals(declaredTypeName)) {
            return;
        }
        applyTypeName(updated);
        rebuilding = true;
        try {
            rebuildPorts();
        } finally {
            rebuilding = false;
        }
        present(() -> {
            if (typeChooser != null) {
                typeChooser.setValue(labelFor(declaredTypeName));
            }
        });
    }

    /** Records the declaration and resolves it, warning once and falling back to Object if it will not load. */
    private void applyTypeName(String typeName) {
        declaredTypeName = typeName;
        Class<?> resolved = resolveType(typeName);
        if (resolved == null) {
            log.warn("{}: declared type {} is not installed here; the port falls back to Object and the"
                    + " declaration is saved unchanged", getClass().getSimpleName(), typeName);
            resolved = Object.class;
        }
        portType = resolved;
    }

    private static String normalise(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return DEFAULT_TYPE_NAME;
        }
        return typeName.trim();
    }

    /**
     * Loads a declared type by name, without initialising it and through the context class loader
     * so a plugin's types are visible.
     *
     * @param typeName the fully-qualified class name to resolve
     * @return the class, or null if it is not available here
     */
    public static Class<?> resolveType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        for (Class<?> core : CORE_TYPES) {
            if (core.getName().equals(typeName)) {
                return core;
            }
        }
        try {
            return Class.forName(typeName, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            // LinkageError as well as the obvious miss: a plugin class can be present while
            // something it extends is not, and a boundary marker must still open either way.
            return null;
        }
    }

    /** How a declared type reads in the chooser: the simple name for a core type, the class name otherwise. */
    static String labelFor(String typeName) {
        for (Class<?> core : CORE_TYPES) {
            if (core.getName().equals(typeName)) {
                return core.getSimpleName();
            }
        }
        return typeName;
    }

    /** The inverse of {@link #labelFor}: anything that is not a core type's simple name is taken as a class name. */
    static String typeNameFor(String label) {
        if (label == null) {
            return DEFAULT_TYPE_NAME;
        }
        String trimmed = label.trim();
        for (Class<?> core : CORE_TYPES) {
            if (core.getSimpleName().equals(trimmed)) {
                return core.getName();
            }
        }
        return trimmed;
    }

    /** Builds this marker's one port. Kept generic so the port's type parameter matches the declared class. */
    final NodeVariable<?> newBoundaryPort() {
        return typedPort(portType);
    }

    private static <T> NodeVariable<T> typedPort(Class<T> type) {
        return new NodeVariable<>(PORT_NAME, type);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new LinkedHashMap<>(super.saveState());
        state.put(TYPE_KEY, declaredTypeName);
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        super.loadState(state);
        setDeclaredType(state.get(TYPE_KEY));
    }

    @Override
    protected void addExtraContent(VBox content) {
        typeChooser = new ComboBox<>();
        typeChooser.setEditable(true);
        typeChooser.setMaxWidth(Double.MAX_VALUE);
        typeChooser.setPromptText("Type");
        List<String> labels = new ArrayList<>();
        for (Class<?> core : CORE_TYPES) {
            labels.add(core.getSimpleName());
        }
        typeChooser.getItems().setAll(labels);
        typeChooser.setValue(labelFor(declaredTypeName));
        typeChooser.setOnAction(event -> setDeclaredType(typeNameFor(typeChooser.getValue())));
        content.getChildren().add(labelled("Type", typeChooser));
    }
}
