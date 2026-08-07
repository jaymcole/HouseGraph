package io.github.jaymcole.housegraph.ui.editor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of how to edit a {@code NodeVariable}'s value as plain text in a node's
 * inline field (see {@link PortView}). A variable's type is only offered a manual-entry
 * field if it's registered here.
 * <p>
 * <b>To make a new type manually editable, add one line to the static block below</b> —
 * nothing else in {@link PortView} or elsewhere needs to change.
 */
public final class ValueEditors {

    public interface Editor<T> {
        T parse(String text);

        String format(T value);
    }

    private static final Map<Class<?>, Editor<?>> EDITORS = new HashMap<>();

    static {
        register(Float.class, Float::parseFloat, String::valueOf);
        register(String.class, String::valueOf, String::valueOf);
        register(Integer.class, Integer::parseInt, String::valueOf);
    }

    private ValueEditors() {
    }

    /** Registers how to parse/format a manually-editable type. Call from the static block above. */
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
