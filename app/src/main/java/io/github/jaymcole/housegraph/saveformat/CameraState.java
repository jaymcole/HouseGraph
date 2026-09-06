package io.github.jaymcole.housegraph.saveformat;

/**
 * A canvas's pan/zoom, captured by {@code GraphCanvas.getCameraState()} and restored by
 * {@code GraphCanvas.setCameraState(CameraState)}. Round-tripped through a save file by
 * {@link GraphFileIO} alongside the graph's nodes and edges, so opening a graph puts the view back
 * where it was left rather than resetting it.
 * <p>
 * Lives here rather than in {@code ui/} because it is part of the save format's root {@code camera}
 * object — {@link GraphFileIO#cameraFromJson} returns it and {@link GraphFileIO#toJson} takes it as
 * a parameter — even though it is also a view concept; {@code GraphCanvas} imports it downward the
 * same way it imports {@code GraphSnapshot}.
 */
public record CameraState(double zoom, double translateX, double translateY) {
    /** What a canvas starts at, and what a save file written before camera state existed restores to. */
    public static final CameraState DEFAULT = new CameraState(1.0, 0.0, 0.0);
}
