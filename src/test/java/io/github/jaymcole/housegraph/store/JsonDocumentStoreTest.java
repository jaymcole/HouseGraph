package io.github.jaymcole.housegraph.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDocumentStoreTest {

    @Test
    void startsEmptyWhenNoFileExists(@TempDir Path dir) {
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        assertEquals("{}", store.get());
    }

    @Test
    void setPersistsAndSurvivesReopen(@TempDir Path dir) {
        Path file = dir.resolve("document.json");
        new JsonDocumentStore(file).set("{\"a\":1}");

        // A fresh instance loads what the first one wrote.
        assertEquals("{\"a\":1}", new JsonDocumentStore(file).get());
    }

    @Test
    void acceptsTopLevelArray(@TempDir Path dir) {
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        store.set("[1,2,3]");
        assertEquals("[1,2,3]", store.get());
    }

    @Test
    void rejectsInvalidJsonAndLeavesDocumentUnchanged(@TempDir Path dir) {
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        store.set("{\"ok\":true}");

        assertThrows(IllegalArgumentException.class, () -> store.set("not json"));
        assertEquals("{\"ok\":true}", store.get());
    }

    @Test
    void corruptFileLoadsAsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("document.json");
        Files.writeString(file, "}{ broken", StandardCharsets.UTF_8);

        assertEquals("{}", new JsonDocumentStore(file).get());
    }

    @Test
    void changeListenersFireWithNewDocument(@TempDir Path dir) {
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        AtomicReference<String> seenA = new AtomicReference<>();
        AtomicReference<String> seenB = new AtomicReference<>();
        store.addChangeListener(seenA::set);
        store.addChangeListener(seenB::set);

        store.set("{\"x\":9}");
        assertEquals("{\"x\":9}", seenA.get());
        assertEquals("{\"x\":9}", seenB.get(), "every registered listener should fire");
    }

    @Test
    void removedListenerStopsFiring(@TempDir Path dir) {
        JsonDocumentStore store = new JsonDocumentStore(dir.resolve("document.json"));
        AtomicReference<String> seen = new AtomicReference<>();
        java.util.function.Consumer<String> listener = seen::set;
        store.addChangeListener(listener);
        store.removeChangeListener(listener);

        store.set("{\"x\":9}");
        assertNull(seen.get(), "a removed listener must not fire");
    }

    @Test
    void concurrentWritesAllPersistOneValidDocument(@TempDir Path dir) throws InterruptedException {
        Path file = dir.resolve("document.json");
        JsonDocumentStore store = new JsonDocumentStore(file);

        int threads = 16;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            int n = i;
            workers[i] = new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    store.set("{\"writer\":" + n + "}");
                }
            });
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        // Whatever the last write was, the store is left holding one well-formed document,
        // and reopening from disk agrees with memory (no torn write).
        String finalDoc = store.get();
        assertTrue(finalDoc.matches("\\{\"writer\":\\d+\\}"), finalDoc);
        assertEquals(finalDoc, new JsonDocumentStore(file).get());
    }
}
