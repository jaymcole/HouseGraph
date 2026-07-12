package io.github.jaymcole.housegraph.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TypeConvertersTest {

    // --- isCompatible ---------------------------------------------------------------

    @Test
    void exactAndAssignableTypesAreCompatible() {
        assertTrue(TypeConverters.isCompatible(Float.class, Float.class));
        assertTrue(TypeConverters.isCompatible(Float.class, Object.class));
        assertTrue(TypeConverters.isCompatible(String.class, Object.class));
    }

    @Test
    void numericAndBooleanPairsAreCompatibleBothWays() {
        assertTrue(TypeConverters.isCompatible(Integer.class, Float.class));
        assertTrue(TypeConverters.isCompatible(Float.class, Integer.class));
        assertTrue(TypeConverters.isCompatible(Double.class, Float.class));
        assertTrue(TypeConverters.isCompatible(Boolean.class, Float.class));
        assertTrue(TypeConverters.isCompatible(Float.class, Boolean.class));
        assertTrue(TypeConverters.isCompatible(Integer.class, Boolean.class));
    }

    @Test
    void unconvertiblePairsAreNotCompatible() {
        assertFalse(TypeConverters.isCompatible(String.class, Float.class));
        assertFalse(TypeConverters.isCompatible(Float.class, String.class));
        assertFalse(TypeConverters.isCompatible(Object.class, Float.class));
    }

    // --- convert: numeric/boolean matrix -------------------------------------------

    @Test
    void widensIntegerToFloatAndDouble() {
        assertEquals(5f, TypeConverters.convert(5, Integer.class, Float.class));
        assertEquals(5.0, TypeConverters.convert(5, Integer.class, Double.class));
    }

    @Test
    void narrowingToIntegerTruncatesTowardZero() {
        assertEquals(3, TypeConverters.convert(3.7, Double.class, Integer.class));
        assertEquals(3, TypeConverters.convert(3.7f, Float.class, Integer.class));
        assertEquals(-3, TypeConverters.convert(-3.7, Double.class, Integer.class));
    }

    @Test
    void bridgesBooleanAndNumbers() {
        assertEquals(1f, TypeConverters.convert(true, Boolean.class, Float.class));
        assertEquals(0, TypeConverters.convert(false, Boolean.class, Integer.class));
        assertEquals(true, TypeConverters.convert(2, Integer.class, Boolean.class));
        assertEquals(false, TypeConverters.convert(0f, Float.class, Boolean.class));
    }

    // --- convert: pass-through behavior --------------------------------------------

    @Test
    void nullPassesThrough() {
        assertNull(TypeConverters.convert(null, Integer.class, Float.class));
    }

    @Test
    void alreadyAssignableValuePassesThroughUnchanged() {
        Float value = 5f;
        assertSame(value, TypeConverters.convert(value, Float.class, Float.class));
        assertSame(value, TypeConverters.convert(value, Float.class, Object.class));
    }

    @Test
    void noConverterFallsBackToRawValue() {
        String value = "unconvertible";
        // No String -> Float converter: the raw value is handed through, preserving the legacy
        // raw-handoff behavior rather than throwing.
        assertSame(value, TypeConverters.convert(value, String.class, Float.class));
    }

    @Test
    void converterIsFoundByRuntimeClassWhenDeclaredTypeMisses() {
        // A value flowing through an Object-typed output still finds a converter for its concrete
        // runtime class.
        assertEquals(5f, TypeConverters.convert(5, Object.class, Float.class));
    }

    // --- register on the fly --------------------------------------------------------

    @Test
    void customConvertersCanBeRegisteredAtRuntime() {
        assertFalse(TypeConverters.hasConverter(StringBuilder.class, String.class));
        TypeConverters.register(StringBuilder.class, String.class, StringBuilder::toString);

        assertTrue(TypeConverters.hasConverter(StringBuilder.class, String.class));
        assertTrue(TypeConverters.isCompatible(StringBuilder.class, String.class));
        assertEquals("hi", TypeConverters.convert(new StringBuilder("hi"), StringBuilder.class, String.class));
    }
}
