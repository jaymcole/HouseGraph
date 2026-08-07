package io.github.jaymcole.housegraph.sdk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of how to edit a {@code NodeVariable}'s value as plain text in a node's inline
 * field. A variable's type is only offered a manual-entry field if it's registered here;
 * an unregistered type can still be wired, just not typed into.
 * <p>
 * The built-in types are registered in the static block below. <b>An out-of-tree node
 * library registers its own types by calling {@link #register} directly</b> — it cannot
 * edit this file, which is why the map is concurrent and why this class lives in the
 * published API rather than in the UI layer. It is the direct counterpart of
 * {@code TypeConverters}, which plays the same role for implicit edge conversions.
 * <p>
 * <b>When to register.</b> The host discovers node classes with {@code initialize = false},
 * so a node's static initializer does <em>not</em> run at scan time — it runs the first time
 * an instance of that node is created. Registering from a node's static block therefore takes
 * effect only once one of its nodes exists, which is soon enough for an inline editor (there
 * is nothing to edit before then) but is worth knowing, because the symptom of assuming
 * otherwise is "my type isn't editable until I place the node twice". Registering from an
 * instance initializer or constructor avoids the question entirely.
 * <p>
 * Registration is last-write-wins: two libraries registering the same type will not corrupt
 * the map, but the second one silently wins.
 */
public final class ValueEditors {

    public interface Editor<T> {
        T parse(String text);

        String format(T value);
    }

    // Concurrent because registration is no longer confined to this class's own static block:
    // node libraries loaded at runtime register from their own initializers, potentially from
    // more than one thread, while the FX thread is reading through isEditable/editorFor.
    private static final Map<Class<?>, Editor<?>> EDITORS = new ConcurrentHashMap<>();

    static {
        register(Float.class, Float::parseFloat, String::valueOf);
        register(String.class, String::valueOf, String::valueOf);
        register(Integer.class, Integer::parseInt, String::valueOf);
    }

    private ValueEditors() {
    }

    /**
     * Registers how to parse and format a manually-editable type.
     *
     * @param type      the value type to make editable
     * @param parser    turns the text the user typed into a value; may throw on bad input
     * @param formatter renders a value back into the text field
     * @param <T>       the value type
     */
    public static <T> void register(Class<T> type, Function<String, T> parser, Function<T, String> formatter) {
        EDITORS.put(type, new Editor<T>() {
            @Override
            public T parse(String text) {
                return parser.apply(text);
            }

            @Override
            public String format(T value) {
                return formatter.apply(value);
            }
        });
    }

    public static boolean isEditable(Class<?> type) {
        return EDITORS.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public static <T> Editor<T> editorFor(Class<T> type) {
        return (Editor<T>) EDITORS.get(type);
    }
}
