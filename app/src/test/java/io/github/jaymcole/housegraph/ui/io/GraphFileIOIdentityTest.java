package io.github.jaymcole.housegraph.ui.io;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one decision {@code ui.io.GraphFileIO} makes on its own: what to do when the file a save is
 * about to overwrite cannot be read, and its module identity therefore cannot be carried across.
 *
 * <p>No canvas and no toolkit — the method under test takes a {@link File} and returns a root, which
 * is the whole reason it is separable from the save that calls it.
 */
class GraphFileIOIdentityTest {

    @TempDir
    Path directory;

    @Test
    void aFileThatIsNotThereYetSimplyHasNoIdentity() throws IOException {
        assertNull(GraphFileIO.existingRoot(directory.resolve("new.json").toFile()),
                "a Save As to a fresh path writes no identity, which is correct");
    }

    @Test
    void anExistingModuleFileHandsItsIdentityOver() throws IOException {
        File file = write("doorbell.json", "{\"version\":3,\"nodes\":[],\"module\":{\"id\":\"m-1\"}}");

        JSONObject root = GraphFileIO.existingRoot(file);

        assertNotNull(root);
        assertEquals("m-1", root.getJSONObject("module").getString("id"));
    }

    @Test
    void aFileWhoseBytesCannotBeReadRefusesTheSaveRatherThanDroppingTheId() throws IOException {
        // A lock, a permission, a network share that went away: the file is probably intact and
        // probably has an id, and overwriting it now would destroy one a later read would have found.
        File file = write("held.json", "{\"version\":3,\"nodes\":[],\"module\":{\"id\":\"m-1\"}}");

        IOException refused = assertThrows(IOException.class,
                () -> GraphFileIO.onUnreadable(file, new IOException("device is busy")));

        assertTrue(refused.getMessage().contains("module identity"), refused.getMessage());
        assertEquals("{\"version\":3,\"nodes\":[],\"module\":{\"id\":\"m-1\"}}",
                Files.readString(file.toPath()), "and nothing was written over it");
    }

    @Test
    void aFileThatIsNotAGraphIsOverwrittenRatherThanCostingTheUserTheirWork() throws IOException {
        File corrupt = write("truncated.json", "{\"version\":3,\"nodes\":[");

        assertNull(GraphFileIO.existingRoot(corrupt),
                "there is no id in there to preserve, and refusing would lose what is on the canvas");
    }

    private File write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
