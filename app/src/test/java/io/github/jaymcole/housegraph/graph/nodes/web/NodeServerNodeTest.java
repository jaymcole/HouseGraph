package io.github.jaymcole.housegraph.graph.nodes.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the Node-server node round-trips its inline configuration through {@code saveState}/
 * {@code loadState} — the only headless surface (start/stop spawns a real {@code node} process and
 * touches the network, which belongs in a manual/integration check, not the unit suite).
 */
class NodeServerNodeTest {

    @Test
    void savesAndReloadsConfiguration() {
        NodeServerNode original = new NodeServerNode();
        original.loadState(Map.of(
                "name", "my-app",
                "directory", "/srv/my-app",
                "command", "node server.js",
                "port", "4000"));

        Map<String, String> saved = original.saveState();

        NodeServerNode reloaded = new NodeServerNode();
        reloaded.loadState(saved);

        assertEquals("my-app", saved.get("name"));
        assertEquals("/srv/my-app", saved.get("directory"));
        assertEquals("node server.js", saved.get("command"));
        assertEquals("4000", saved.get("port"));
        assertEquals(saved, reloaded.saveState(), "config should survive a save/load round-trip unchanged");
    }

    @Test
    void invalidPortFallsBackToDefault() {
        NodeServerNode node = new NodeServerNode();
        node.loadState(Map.of("name", "app", "port", "not-a-number"));

        assertEquals("3000", node.saveState().get("port"), "an unparseable port should fall back to the default");
    }
}
