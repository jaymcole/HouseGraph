package io.github.jaymcole.housegraph.graph;

import io.github.jaymcole.housegraph.graph.ObjectProperties.Property;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectPropertiesTest {

    record Point(String label, int x, int y) {
    }

    static final class Bean {
        public String getName() {
            return "bean";
        }

        public boolean isActive() {
            return true;
        }

        // Not a property: takes an argument.
        public String getThing(int i) {
            return "x";
        }
    }

    static final class Fields {
        public String tag = "t";
        public int count = 3;
        private String hidden = "h"; // not public - excluded
    }

    @Test
    void recordExposesComponentsInDeclaredOrderWithBoxedPrimitives() {
        assertEquals(
                List.of(new Property("label", String.class),
                        new Property("x", Integer.class),
                        new Property("y", Integer.class)),
                ObjectProperties.of(Point.class));
    }

    @Test
    void beanExposesGettersSortedByNameWithBoxedBooleans() {
        assertEquals(
                List.of(new Property("active", Boolean.class),
                        new Property("name", String.class)),
                ObjectProperties.of(Bean.class));
    }

    @Test
    void publicFieldsAreExposedAndPrivateOnesAreNot() {
        assertEquals(
                List.of(new Property("count", Integer.class),
                        new Property("tag", String.class)),
                ObjectProperties.of(Fields.class));
    }

    static final class WithStatics {
        public static final int CONSTANT = 42;
        public static String label() {
            return "static-getter-ish";
        }
        public String instanceValue = "v";
    }

    @Test
    void staticFieldsAndMethodsAreNotProperties() {
        // Only the instance field survives - the static field/method are class-level, not
        // properties of a value (this is why decomposing a Float must not expose MIN_VALUE).
        assertEquals(List.of(new Property("instanceValue", String.class)),
                ObjectProperties.of(WithStatics.class));
    }

    @Test
    void wrapperTypesExposeNoStaticConstants() {
        assertFalse(ObjectProperties.of(Float.class).stream().anyMatch(p -> p.name().equals("MIN_VALUE")),
                "a Float's static MIN_VALUE constant must not appear as a property");
        assertTrue(ObjectProperties.of(Integer.class).isEmpty(),
                "an Integer has no instance properties to decompose");
    }

    @Test
    void objectAndNullHaveNoProperties() {
        assertTrue(ObjectProperties.of(Object.class).isEmpty());
        assertTrue(ObjectProperties.of(null).isEmpty());
    }

    @Test
    void readResolvesRecordGetterAndFieldValues() {
        assertEquals("here", ObjectProperties.read(new Point("here", 1, 2), "label"));
        assertEquals(1, ObjectProperties.read(new Point("here", 1, 2), "x"));
        assertEquals("bean", ObjectProperties.read(new Bean(), "name"));
        assertEquals(true, ObjectProperties.read(new Bean(), "active"));
        assertEquals("t", ObjectProperties.read(new Fields(), "tag"));
    }

    @Test
    void readIsNullSafeForMissingPropertyOrNullInstance() {
        assertNull(ObjectProperties.read(new Point("here", 1, 2), "nope"));
        assertNull(ObjectProperties.read(null, "label"));
    }
}
