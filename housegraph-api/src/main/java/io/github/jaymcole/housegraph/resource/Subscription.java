package io.github.jaymcole.housegraph.resource;

/** A handle for undoing a {@link ResourceRegistry#subscribe} — call {@link #cancel()} to stop receiving events. */
@FunctionalInterface
public interface Subscription {

    void cancel();
}
