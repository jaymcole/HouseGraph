package io.github.jaymcole.housegraph.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaymcole.housegraph.graph.TypeConverters.ConversionSafety;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        // Nothing bridges a collection to a number in either direction, and an erased Object
        // output can't be narrowed to a number - the matrix only narrows Object to Map.
        assertFalse(TypeConverters.isCompatible(Object.class, Float.class));
        assertFalse(TypeConverters.isCompatible(Map.class, Float.class));
        assertFalse(TypeConverters.isCompatible(List.class, Boolean.class));
    }

    @Test
    void stringIsBridgedToAndFromNumbers() {
        assertTrue(TypeConverters.isCompatible(Float.class, String.class));
        assertTrue(TypeConverters.isCompatible(Integer.class, String.class));
        assertTrue(TypeConverters.isCompatible(String.class, Float.class));
        assertTrue(TypeConverters.isCompatible(String.class, Integer.class));
        assertTrue(TypeConverters.isCompatible(String.class, Long.class));
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
        List<String> value = List.of("unconvertible");
        // No List -> Float converter, by declared type or by runtime class: the raw value is handed
        // through, preserving the legacy raw-handoff behavior rather than throwing.
        assertSame(value, TypeConverters.convert(value, List.class, Float.class));
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
        TypeConverters.register(StringBuilder.class, String.class, ConversionSafety.SAFE, StringBuilder::toString);

        assertTrue(TypeConverters.hasConverter(StringBuilder.class, String.class));
        assertTrue(TypeConverters.isCompatible(StringBuilder.class, String.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(StringBuilder.class, String.class));
        assertEquals("hi", TypeConverters.convert(new StringBuilder("hi"), StringBuilder.class, String.class));
    }

    // --- classify: safety tiers -----------------------------------------------------

    @Test
    void classifyReportsSafeForAssignableAndWideningPairs() {
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Float.class, Float.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Float.class, Object.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Integer.class, Float.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Integer.class, Double.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Boolean.class, Float.class));
    }

    @Test
    void classifyReportsCautiousForNarrowingPairs() {
        assertEquals(ConversionSafety.CAUTIOUS, TypeConverters.classify(Double.class, Float.class));
        assertEquals(ConversionSafety.CAUTIOUS, TypeConverters.classify(Double.class, Integer.class));
        assertEquals(ConversionSafety.CAUTIOUS, TypeConverters.classify(Float.class, Integer.class));
    }

    @Test
    void classifyReportsRiskyForNumberToBoolean() {
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(Integer.class, Boolean.class));
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(Float.class, Boolean.class));
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(Double.class, Boolean.class));
    }

    @Test
    void classifyReportsIncompatibleWhenNoPathExists() {
        assertEquals(ConversionSafety.INCOMPATIBLE, TypeConverters.classify(Object.class, Float.class));
        assertEquals(ConversionSafety.INCOMPATIBLE, TypeConverters.classify(Map.class, Float.class));
        assertEquals(ConversionSafety.INCOMPATIBLE, TypeConverters.classify(List.class, Boolean.class));
    }

    // --- classify/convert: the String row -------------------------------------------

    @Test
    void classifyReportsSafeForNumberToStringAndCautiousForObjectToString() {
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Float.class, String.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Integer.class, String.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Double.class, String.class));
        assertEquals(ConversionSafety.SAFE, TypeConverters.classify(Long.class, String.class));
        assertEquals(ConversionSafety.CAUTIOUS, TypeConverters.classify(Object.class, String.class));
    }

    @Test
    void classifyReportsRiskyForParsingStringToNumber() {
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(String.class, Float.class));
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(String.class, Integer.class));
        assertEquals(ConversionSafety.RISKY, TypeConverters.classify(String.class, Long.class));
    }

    @Test
    void rendersNumbersAsTextAndParsesThemBack() {
        assertEquals("2.5", TypeConverters.convert(2.5f, Float.class, String.class));
        assertEquals("7", TypeConverters.convert(7, Integer.class, String.class));
        assertEquals(2.5f, TypeConverters.convert("2.5", String.class, Float.class));
        assertEquals(7, TypeConverters.convert("7", String.class, Integer.class));
    }

    @Test
    void parsingTextThatIsNotANumberThrowsAtHandoff() {
        // Why String -> number is RISKY rather than CAUTIOUS: it is the one built-in family that
        // fails outright instead of losing precision, and the connection is allowed regardless -
        // the gate is "not INCOMPATIBLE", so this surfaces when a value propagates, not on connect.
        assertThrows(NumberFormatException.class,
                () -> TypeConverters.convert("unconvertible", String.class, Float.class));
    }
}
