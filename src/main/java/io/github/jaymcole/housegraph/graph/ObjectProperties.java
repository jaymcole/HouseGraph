package io.github.jaymcole.housegraph.graph;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflects the readable "properties" of a type so a node (the object decomposer) can
 * expose each one as its own output. Deliberately free of any JavaFX/graph dependency so
 * it can be unit-tested headlessly.
 * <p>
 * Property discovery ({@link #of}) is driven by the <em>declared</em> type, so it's
 * deterministic and reproducible across a save/load without ever running the graph:
 * <ul>
 *   <li>a {@code record} exposes its components, in declared order;</li>
 *   <li>any other type exposes its public JavaBean getters ({@code getX}/{@code isX}) and
 *       public fields, deduplicated by name and sorted by name for a stable order.</li>
 * </ul>
 * Value extraction ({@link #read}) instead resolves the accessor against the live value's
 * runtime class by name, so it keeps working after a load (when only a property's name and
 * type were persisted, not a bound accessor).
 */
public final class ObjectProperties {

    /** One readable property: the name it's exposed under and its declared value type. */
    public record Property(String name, Class<?> type) {
    }

    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    private ObjectProperties() {
    }

    /**
     * The reference type a property port carries for {@code type} - the boxed type for a
     * primitive (reflective reads box anyway, and a wrapper survives a save/load via
     * {@code Class.forName}, which can't resolve {@code "int"} and friends).
     */
    private static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? BOXED.get(type) : type;
    }

    /** The properties of {@code type}, in a stable order - see the class Javadoc for the rules. */
    public static List<Property> of(Class<?> type) {
        if (type == null || type == Object.class) {
            return List.of();
        }
        if (type.isRecord()) {
            List<Property> properties = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                properties.add(new Property(component.getName(), box(component.getType())));
            }
            return properties;
        }
        // Getters first, then fields not already covered by a getter; keyed by name for
        // dedup, then sorted so the order is stable across runs (drives save/load indices).
        // Only instance members: a static field/method (e.g. Float.MIN_VALUE) is a class
        // constant, not a property of a value, and must never become a decomposed output.
        Map<String, Property> byName = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            String name = getterPropertyName(method);
            if (name != null) {
                byName.putIfAbsent(name, new Property(name, box(method.getReturnType())));
            }
        }
        for (Field field : type.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                byName.putIfAbsent(field.getName(), new Property(field.getName(), box(field.getType())));
            }
        }
        List<Property> properties = new ArrayList<>(byName.values());
        properties.sort((a, b) -> a.name().compareTo(b.name()));
        return properties;
    }

    /**
     * Reads property {@code name} from {@code instance}, resolving the accessor against the
     * instance's runtime class. Returns null if {@code instance} is null, no such property
     * exists, or reading it throws - a decomposer output simply goes empty rather than
     * failing the whole node.
     */
    public static Object read(Object instance, String name) {
        if (instance == null || name == null) {
            return null;
        }
        Class<?> type = instance.getClass();
        try {
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getName().equals(name)) {
                        return invoke(component.getAccessor(), instance);
                    }
                }
            }
            Method getter = findGetter(type, name);
            if (getter != null) {
                return invoke(getter, instance);
            }
            Field field = type.getField(name);
            field.setAccessible(true); // the property is public but its declaring class may not be
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Invokes an accessor, first making it accessible so a public method on a non-public class still reads. */
    private static Object invoke(Method accessor, Object instance) throws ReflectiveOperationException {
        accessor.setAccessible(true);
        return accessor.invoke(instance);
    }

    /** The property name a method exposes as a JavaBean getter, or null if it isn't one. */
    private static String getterPropertyName(Method method) {
        if (method.getParameterCount() != 0
                || method.getReturnType() == void.class
                || Modifier.isStatic(method.getModifiers())
                || method.getDeclaringClass() == Object.class) {
            return null;
        }
        String methodName = method.getName();
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    private static Method findGetter(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(getterPropertyName(method))) {
                return method;
            }
        }
        return null;
    }

    private static String decapitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
