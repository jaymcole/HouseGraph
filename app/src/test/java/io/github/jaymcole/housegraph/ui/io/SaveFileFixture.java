package io.github.jaymcole.housegraph.ui.io;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.ui.snapshot.GraphSnapshot;
import org.json.JSONObject;

/**
 * Builds real save files for tests that live outside this package.
 *
 * <p>{@code GraphFileIO}'s JSON conversion is package-private — the public writer takes a
 * {@code GraphCanvas} — so a headless test has no way to produce a save file without either a
 * toolkit or a hand-written JSON literal that would drift from the format. This exposes the
 * conversion to tests and nothing else; production code has no business here.
 */
public final class SaveFileFixture {

    private SaveFileFixture() {
    }

    /** The same JSON a File ▸ Save of {@code snapshot} would write. */
    public static JSONObject toJson(GraphSnapshot snapshot, NodeRegistry registry) {
        return GraphFileIO.toJson(snapshot, registry);
    }

    /** The format version a file written today carries. */
    public static int currentVersion() {
        return GraphFileIO.CURRENT_VERSION;
    }
}
