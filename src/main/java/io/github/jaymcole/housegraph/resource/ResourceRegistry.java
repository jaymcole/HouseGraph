package io.github.jaymcole.housegraph.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The coordination hub that lets long-lived resource nodes be referenced from anywhere
 * on the graph by name — not by wiring (the pattern from the design discussion, à la
 * Node-RED config nodes). It offers two complementary lookups, both keyed by name:
 * <ul>
 *   <li><b>Object lookup</b> ({@link #register}/{@link #find}) — a resource publishes
 *       itself so action nodes can fetch it and call methods (e.g. a send-message node
 *       finding its bot).</li>
 *   <li><b>Event pub/sub</b> ({@link #publish}/{@link #subscribe}) — a resource pushes
 *       events under its name so trigger nodes are driven by them. Subscription is by
 *       name, not by instance, so order doesn't matter (you can listen before the
 *       resource exists) and a resource reconnecting doesn't break listeners.</li>
 * </ul>
 * Thread-safe: events may be published from a resource's own thread while nodes
 * register/subscribe from the UI thread. A single app-wide {@link #shared()} instance is
 * used today; it could become per-document later without touching callers.
 */
public final class ResourceRegistry {

    private static final ResourceRegistry SHARED = new ResourceRegistry();

    private final Map<String, Object> resources = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    public static ResourceRegistry shared() {
        return SHARED;
    }

    // --- Object lookup ------------------------------------------------------------

    /** Publishes a live resource under {@code name} so other nodes can {@link #find} it. */
    public void register(String name, Object resource) {
        resources.put(name, resource);
    }

    public void unregister(String name) {
        resources.remove(name);
    }

    /** The registered resource under {@code name}, if it's of the expected type. */
    public <T> Optional<T> find(String name, Class<T> type) {
        Object resource = resources.get(name);
        return type.isInstance(resource) ? Optional.of(type.cast(resource)) : Optional.empty();
    }

    /** The names of currently-registered resources, sorted — for populating a picker. */
    public List<String> activeNames() {
        return new ArrayList<>(new TreeSet<>(resources.keySet()));
    }

    // --- Event pub/sub ------------------------------------------------------------

    /** Delivers {@code payload} to every listener currently subscribed to {@code name}. */
    public void publish(String name, String payload) {
        CopyOnWriteArrayList<Consumer<String>> listeners = subscribers.get(name);
        if (listeners != null) {
            for (Consumer<String> listener : listeners) {
                listener.accept(payload);
            }
        }
    }

    /** Subscribes {@code listener} to events published under {@code name}; cancel via the returned handle. */
    public Subscription subscribe(String name, Consumer<String> listener) {
        subscribers.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<String>> listeners = subscribers.get(name);
            if (listeners != null) {
                listeners.remove(listener);
            }
        };
    }
}
