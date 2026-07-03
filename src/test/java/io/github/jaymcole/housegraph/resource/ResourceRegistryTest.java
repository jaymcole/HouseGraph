package io.github.jaymcole.housegraph.resource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceRegistryTest {

    @Test
    void publishReachesOnlySubscribersOfThatName() {
        ResourceRegistry registry = new ResourceRegistry();
        List<String> received = new ArrayList<>();
        registry.subscribe("echo", received::add);

        registry.publish("echo", "tick 1");
        registry.publish("other", "ignored");

        assertEquals(List.of("tick 1"), received);
    }

    @Test
    void aCancelledSubscriptionStopsReceiving() {
        ResourceRegistry registry = new ResourceRegistry();
        List<String> received = new ArrayList<>();
        Subscription subscription = registry.subscribe("echo", received::add);

        registry.publish("echo", "1");
        subscription.cancel();
        registry.publish("echo", "2");

        assertEquals(List.of("1"), received);
    }

    @Test
    void activeNamesAreSortedAndUnregisterRemovesThem() {
        ResourceRegistry registry = new ResourceRegistry();
        registry.register("bravo");
        registry.register("alpha");
        assertEquals(List.of("alpha", "bravo"), registry.activeNames());

        registry.unregister("alpha");
        assertEquals(List.of("bravo"), registry.activeNames());
    }

    @Test
    void publishingWithNoSubscribersIsHarmless() {
        assertDoesNotThrow(() -> new ResourceRegistry().publish("nobody-home", "x"));
    }
}
