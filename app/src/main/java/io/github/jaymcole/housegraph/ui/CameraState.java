package io.github.jaymcole.housegraph.ui;

/**
 * A {@link GraphCanvas}'s pan/zoom, captured by {@link GraphCanvas#getCameraState()} and restored by
 * {@link GraphCanvas#setCameraState(CameraState)}. Round-tripped through a save file by
 * {@code GraphFileIO} alongside the graph's nodes and edges, so opening a graph puts the view back
 * where it was left rather than resetting it.
 */
public record CameraState(double zoom, double translateX, double translateY) {
    /** What a canvas starts at, and what a save file written before camera state existed restores to. */
    public static final CameraState DEFAULT = new CameraState(1.0, 0.0, 0.0);
}
