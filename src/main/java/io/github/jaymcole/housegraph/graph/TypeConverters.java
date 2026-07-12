package io.github.jaymcole.housegraph.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of <em>hidden</em> value converters that makes data anchors "more accepting".
 *
 * <p>A data anchor's type is a {@link Class}. Historically an {@link Edge} could only join an
 * output to an input when the input type was {@link Class#isAssignableFrom assignable from} the
 * output type — so an {@code Integer} output could never feed a {@code Float} input, because the
 * boxed numeric classes are unrelated {@code final} types. Users had to drop in an explicit
 * converter <em>node</em> (see {@code graph.nodes.converters}) to bridge them.
 *
 * <p>This registry relaxes that: a connection is compatible when the types match exactly / by
 * subtyping <em>or</em> a converter is registered for the {@code (from, to)} pair, and at value-
 * handoff time the registered converter transforms the value transparently — the user never sees
 * or wires the conversion. Explicit converter nodes still exist for conversions the hidden matrix
 * deliberately does not cover (e.g. {@code *}&nbsp;&rarr;&nbsp;{@code String}) and for making a
 * conversion a visible, first-class step in a graph.
 *
 * <p><b>Extending it.</b> The built-in matrix is seeded in the static block below, but
 * {@link #register(Class, Class, Converter)} is public and thread-safe, so additional converters
 * (e.g. from a third-party plugin) can be registered on the fly at runtime. The backing map is a
 * {@link ConcurrentHashMap} because edges are attached from the UI thread while values propagate on
 * the engine's execution threads.
 *
 * <p><b>A note on the built-in numeric/boolean matrix.</b> It interconverts {@link Integer},
 * {@link Double}, {@link Float}, and {@link Boolean} in both directions. Some of these conversions
 * are intentionally <em>lossy</em>: {@code Double}/{@code Float}&nbsp;&rarr;&nbsp;{@code Integer}
 * truncates toward zero, and any numeric&nbsp;&rarr;&nbsp;{@code Boolean} collapses to
 * "non-zero&nbsp;=&nbsp;true". That looseness is the point — the feature trades exactness for
 * convenience — but keep it in mind when a downstream node reads the coerced value.
 *
 * <p>This class lives in {@code graph/} and imports no JavaFX, so the compatibility check and the
 * conversions stay headless-testable. The UI ({@code GraphCanvas.isValidConnection}) and the engine
 * ({@code NodeGraph.attachEdge}/{@code NodeGraph.propagateValue}) both route through it, keeping the
 * drag-time gate, the authoritative gate, and the value handoff consistent.
 */
public final class TypeConverters {

    /** Transforms a value of the {@code from} type into the {@code to} type. */
    @FunctionalInterface
    public interface Converter<F, T> {
        T convert(F value);
    }

    private record Key(Class<?> from, Class<?> to) {
    }

    private static final Map<Key, Converter<?, ?>> CONVERTERS = new ConcurrentHashMap<>();

    static {
        // Integer -> Double / Float / Boolean
        register(Integer.class, Double.class, Integer::doubleValue);
        register(Integer.class, Float.class, Integer::floatValue);
        register(Integer.class, Boolean.class, i -> i != 0);
        // Double -> Integer (truncates) / Float / Boolean
        register(Double.class, Integer.class, Double::intValue);
        register(Double.class, Float.class, Double::floatValue);
        register(Double.class, Boolean.class, d -> d != 0.0);
        // Float -> Integer (truncates) / Double / Boolean
        register(Float.class, Integer.class, Float::intValue);
        register(Float.class, Double.class, Float::doubleValue);
        register(Float.class, Boolean.class, f -> f != 0.0f);
        // Boolean -> Integer / Double / Float (true = 1, false = 0)
        register(Boolean.class, Integer.class, b -> b ? 1 : 0);
        register(Boolean.class, Double.class, b -> b ? 1.0 : 0.0);
        register(Boolean.class, Float.class, b -> b ? 1.0f : 0.0f);
    }

    private TypeConverters() {
    }

    /**
     * Registers a converter for the {@code (from, to)} type pair, replacing any existing one. Safe
     * to call at runtime (e.g. from a plugin); this is the extension point that lets anchors accept
     * new types without touching the engine or UI.
     *
     * @param from      the source (output) type
     * @param to        the target (input) type
     * @param converter transforms a {@code from} value into a {@code to} value
     */
    public static <F, T> void register(Class<F> from, Class<T> to, Converter<F, T> converter) {
        CONVERTERS.put(new Key(from, to), converter);
    }

    /**
     * Whether a converter is registered for the exact {@code (from, to)} type pair. This is the
     * registry-only lookup; for the full "can these anchors connect?" question use
     * {@link #isCompatible(Class, Class)}, which also allows assignable types.
     *
     * @param from the source (output) type
     * @param to   the target (input) type
     * @return true if a converter exists for this pair
     */
    public static boolean hasConverter(Class<?> from, Class<?> to) {
        return CONVERTERS.containsKey(new Key(from, to));
    }

    /**
     * Whether an output of type {@code from} may feed an input of type {@code to}: either the input
     * type is assignable from the output type (exact match, a subtype, or an {@code Object} input
     * that accepts anything) or a converter is registered for the pair. This is the single predicate
     * shared by the drag-time UI gate and the authoritative engine gate.
     *
     * @param from the source (output) type
     * @param to   the target (input) type
     * @return true if the connection is allowed
     */
    public static boolean isCompatible(Class<?> from, Class<?> to) {
        return to.isAssignableFrom(from) || hasConverter(from, to);
    }

    /**
     * Coerces {@code value} for storage in a {@code to}-typed anchor. Returns the value unchanged
     * when it already fits the target (exact type, a subtype, or an {@code Object} target), applies
     * the registered converter otherwise, and — when no converter matches — passes the raw value
     * through so legacy raw-handoff paths (e.g. a decomposer emitting into an {@code Object}-shaped
     * consumer) keep working exactly as before. Null-safe.
     *
     * <p>The declared {@code from} type is tried first; if that misses, the value's own runtime
     * class is tried, so a value flowing through an {@code Object}-typed output still finds a
     * converter for its concrete type.
     *
     * @param value the value produced by the source anchor (may be null)
     * @param from  the declared type of the source anchor
     * @param to    the declared type of the target anchor
     * @return the value coerced to {@code to} where possible, otherwise the value unchanged
     */
    @SuppressWarnings("unchecked")
    public static Object convert(Object value, Class<?> from, Class<?> to) {
        if (value == null) {
            return null;
        }
        if (to.isAssignableFrom(value.getClass())) {
            return value;
        }
        Converter<Object, ?> converter = (Converter<Object, ?>) CONVERTERS.get(new Key(from, to));
        if (converter == null) {
            converter = (Converter<Object, ?>) CONVERTERS.get(new Key(value.getClass(), to));
        }
        return converter != null ? converter.convert(value) : value;
    }
}
