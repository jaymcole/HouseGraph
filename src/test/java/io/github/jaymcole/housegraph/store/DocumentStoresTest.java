package io.github.jaymcole.housegraph.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class DocumentStoresTest {

    @Test
    void sameFileYieldsSameInstance(@TempDir Path dir) {
        Path file = dir.resolve("document.json");
        assertSame(DocumentStores.forFile(file), DocumentStores.forFile(file),
                "one instance per file, so same-name stores stay consistent");
    }

    @Test
    void aliasingPathsCollapseToOneInstance(@TempDir Path dir) {
        Path direct = dir.resolve("document.json");
        Path aliased = dir.resolve("sub").resolve("..").resolve("document.json");
        assertSame(DocumentStores.forFile(direct), DocumentStores.forFile(aliased),
                "paths that normalise to the same file must share an instance");
    }

    @Test
    void differentFilesYieldDifferentInstances(@TempDir Path dir) {
        assertNotSame(DocumentStores.forFile(dir.resolve("a.json")),
                DocumentStores.forFile(dir.resolve("b.json")));
    }
}
