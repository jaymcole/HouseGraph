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
 * <p><b>Conversion safety.</b> Every converter is registered with a {@link ConversionSafety} level
 * describing how faithful the conversion is — {@link ConversionSafety#SAFE SAFE} (lossless or
 * widening), {@link ConversionSafety#CAUTIOUS CAUTIOUS} (predictable loss, e.g. truncation), or
 * {@link ConversionSafety#RISKY RISKY} (drastic/surprising loss). {@link #classify(Class, Class)}
 * reports the level for a pair (or {@link ConversionSafety#INCOMPATIBLE INCOMPATIBLE} when no path
 * exists); the UI uses it to colour anchors while an edge is being dragged. The level is advisory
 * for connection purposes — both gates allow anything that is not {@code INCOMPATIBLE}.
 *
 * <p><b>Extending it.</b> The built-in matrix is seeded in the static block below, but
 * {@link #register(Class, Class, ConversionSafety, Converter)} is public and thread-safe, so
 * additional converters (e.g. from a third-party plugin) can be registered on the fly at runtime,
 * each declaring its own safety level. The backing map is a {@link ConcurrentHashMap} because edges
 * are attached from the UI thread while values propagate on the engine's execution threads.
 *
 * <p><b>A note on the built-in numeric/boolean matrix.</b> It interconverts {@link Integer},
 * {@link Double}, {@link Float}, and {@link Boolean} in both directions. Widening / lossless steps
 * are {@code SAFE}; numeric narrowing that truncates ({@code Double}/{@code Float}&nbsp;&rarr;&nbsp;
 * {@code Integer}) and precision loss ({@code Double}&nbsp;&rarr;&nbsp;{@code Float}) are
 * {@code CAUTIOUS}; collapsing a number to a flag (number&nbsp;&rarr;&nbsp;{@code Boolean}, where
 * everything non-zero becomes {@code true}) is {@code RISKY}.
 *
 * <p>This class lives in {@code graph/} and imports no JavaFX, so the compatibility check and the
 * conversions stay headless-testable. The UI ({@code GraphCanvas.isValidConnection} /
 * drag-time anchor colouring) and the engine ({@code NodeGraph.attachEdge}/
 * {@code NodeGraph.propagateValue}) both route through it, keeping the drag-time gate, the
 * authoritative gate, and the value handoff consistent.
 */
public final class TypeConverters {

    /**
     * How faithful a conversion is, used to colour anchors during an edge drag. Converters are
     * registered as {@link #SAFE}, {@link #CAUTIOUS}, or {@link #RISKY}; {@link #INCOMPATIBLE} is a
     * classify-only result meaning "no assignable path and no converter".
     */
    public enum ConversionSafety {
        /** Lossless or widening (e.g. {@code Integer} &rarr; {@code Double}); assignable pairs too. */
        SAFE,
        /** Predictable loss, e.g. numeric narrowing/truncation ({@code Double} &rarr; {@code Integer}). */
        CAUTIOUS,
        /** Drastic or surprising loss, e.g. collapsing a number to a {@code Boolean} flag. */
        RISKY,
        /** No conversion exists and the types are not assignable — the connection is not allowed. */
        INCOMPATIBLE
    }

    /** Transforms a value of the {@code from} type into the {@code to} type. */
    @FunctionalInterface
    public interface Converter<F, T> {
        T convert(F value);
    }

    private record Key(Class<?> from, Class<?> to) {
    }

    private record Registration(Converter<?, ?> converter, ConversionSafety safety) {
    }

    private static final Map<Key, Registration> CONVERTERS = new ConcurrentHashMap<>();

    static {
        // Widening / lossless -> SAFE.
        register(Integer.class, Double.class, ConversionSafety.SAFE, Integer::doubleValue);
        register(Integer.class, Float.class, ConversionSafety.SAFE, Integer::floatValue);
        register(Float.class, Double.class, ConversionSafety.SAFE, Float::doubleValue);
        register(Boolean.class, Integer.class, ConversionSafety.SAFE, b -> b ? 1 : 0);
        register(Boolean.class, Double.class, ConversionSafety.SAFE, b -> b ? 1.0 : 0.0);
        register(Boolean.class, Float.class, ConversionSafety.SAFE, b -> b ? 1.0f : 0.0f);
        // Numeric narrowing / precision loss -> CAUTIOUS.
        register(Double.class, Float.class, ConversionSafety.CAUTIOUS, Double::floatValue);
        register(Double.class, Integer.class, ConversionSafety.CAUTIOUS, Double::intValue);
        register(Float.class, Integer.class, ConversionSafety.CAUTIOUS, Float::intValue);
        // Collapsing a number to a flag (any non-zero -> true) -> RISKY.
        register(Integer.class, Boolean.class, ConversionSafety.RISKY, i -> i != 0);
        register(Double.class, Boolean.class, ConversionSafety.RISKY, d -> d != 0.0);
        register(Float.class, Boolean.class, ConversionSafety.RISKY, f -> f != 0.0f);
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
     * @param safety    how faithful the conversion is (drives anchor colouring during a drag)
     * @param converter transforms a {@code from} value into a {@code to} value
     */
    public static <F, T> void register(Class<F> from, Class<T> to, ConversionSafety safety, Converter<F, T> converter) {
        CONVERTERS.put(new Key(from, to), new Registration(converter, safety));
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
     * Classifies how an output of type {@code from} would feed an input of type {@code to}:
     * {@link ConversionSafety#SAFE} when the input is assignable from the output (exact match, a
     * subtype, or an {@code Object} input) or a safe converter exists, the converter's own level
     * ({@code CAUTIOUS}/{@code RISKY}) when a lossy converter exists, and
     * {@link ConversionSafety#INCOMPATIBLE} when neither applies. The UI uses this to colour a
     * candidate anchor (green / yellow / orange / red) while an edge is dragged.
     *
     * @param from the source (output) type
     * @param to   the target (input) type
     * @return the safety level of the connection
     */
    public static ConversionSafety classify(Class<?> from, Class<?> to) {
        if (to.isAssignableFrom(from)) {
            return ConversionSafety.SAFE;
        }
        Registration registration = CONVERTERS.get(new Key(from, to));
        return registration != null ? registration.safety() : ConversionSafety.INCOMPATIBLE;
    }

    /**
     * Whether an output of type {@code from} may feed an input of type {@code to}: either the input
     * type is assignable from the output type (exact match, a subtype, or an {@code Object} input
     * that accepts anything) or a converter is registered for the pair. This is the single predicate
     * shared by the drag-time UI gate and the authoritative engine gate; it is exactly
     * "{@link #classify(Class, Class)} is not {@link ConversionSafety#INCOMPATIBLE}".
     *
     * @param from the source (output) type
     * @param to   the target (input) type
     * @return true if the connection is allowed
     */
    public static boolean isCompatible(Class<?> from, Class<?> to) {
        return classify(from, to) != ConversionSafety.INCOMPATIBLE;
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
        Registration registration = CONVERTERS.get(new Key(from, to));
        if (registration == null) {
            registration = CONVERTERS.get(new Key(value.getClass(), to));
        }
        return registration != null ? ((Converter<Object, ?>) registration.converter()).convert(value) : value;
    }
}
