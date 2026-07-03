package io.github.jaymcole.housegraph.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A small name-keyed event bus that lets long-lived resource nodes be referenced from
 * anywhere on the graph — not by wiring, but by name (the pattern from the design
 * discussion, à la Node-RED config nodes).
 * <ul>
 *   <li>A resource node {@link #register}s a name while it's live, so pickers can list it.</li>
 *   <li>It {@link #publish}es events under that name.</li>
 *   <li>Any node {@link #subscribe}s to a name to be driven by those events.</li>
 * </ul>
 * Subscription is by name, not by resource instance, so ordering doesn't matter (you can
 * listen before the resource exists) and a resource restarting doesn't break listeners.
 * <p>
 * Thread-safe: events may be published from a resource's own thread while nodes
 * subscribe/unsubscribe from the UI thread. A single app-wide {@link #shared()} instance
 * is used today; it could become per-document later without touching callers.
 */
public final class ResourceRegistry {

    private static final ResourceRegistry SHARED = new ResourceRegistry();

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    public static ResourceRegistry shared() {
        return SHARED;
    }

    /** Announces a live resource under {@code name}, so listeners can discover it via {@link #activeNames()}. */
    public void register(String name) {
        active.add(name);
    }

    public void unregister(String name) {
        active.remove(name);
    }

    /** The names of currently-live resources, sorted — for populating a picker. */
    public List<String> activeNames() {
        return new ArrayList<>(new TreeSet<>(active));
    }

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
