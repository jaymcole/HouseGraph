package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugin.GraphDependencyCheck;
import io.github.jaymcole.housegraph.saveformat.GraphFileIO;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds a module file by the stable id in its root — the one component here that touches disk.
 *
 * <h2>Where resolution looks</h2>
 * Every {@code *.json} directly under each search root, newest search root last. The default
 * library searches {@link AppDirectories#modules()} alone; {@link #over(List, NodeRegistry)} adds
 * roots for a caller that has somewhere else to look, which is how {@code housegraph validate}
 * finds a module sitting beside the graph that references it.
 * <p>
 * A file is matched by its {@code module.id}, never by its name or position, so moving or renaming
 * a module within a search root keeps every consumer working. A saved <em>path hint</em> is offered
 * to {@link #resolve(String, String)} as a shortcut, and is trusted only as far as the id it finds
 * there: a file at the hinted path carrying a different id is a different module, and is ignored
 * rather than silently substituted. See
 * {@code docs/decisions/0011-modules-are-referenced-by-id.md}.
 *
 * <h2>The index is a snapshot</h2>
 * The scan runs once and is cached, because deriving an interface builds every node in every module
 * file. Call {@link #refresh()} after something has changed on disk. Nothing here watches the
 * filesystem.
 *
 * <h2>An unreadable file costs only itself</h2>
 * A file that will not parse, or that carries no module id, is logged and skipped: one broken graph
 * in the modules directory must not make every other module unresolvable.
 */
public final class ModuleLibrary implements ModuleDirectory {

    private static final Logger log = Log.get(ModuleLibrary.class);

    private final List<Path> searchRoots;
    private final NodeRegistry registry;

    /** id -> parsed root, built by {@link #index()}; null until the first scan. */
    private Map<String, JSONObject> rootsById;
    /** id -> where that root was read from, index-aligned in meaning with {@link #rootsById}. */
    private Map<String, Path> filesById;

    private ModuleLibrary(List<Path> searchRoots, NodeRegistry registry) {
        this.searchRoots = List.copyOf(searchRoots);
        this.registry = registry;
    }

    /**
     * The library every ordinary caller wants: {@link AppDirectories#modules()} and nothing else.
     *
     * @param registry resolves each module's node types so its interface can be derived
     * @return a library over the machine's modules directory
     */
    public static ModuleLibrary defaultLibrary(NodeRegistry registry) {
        return new ModuleLibrary(List.of(AppDirectories.get().modules()), registry);
    }

    /**
     * A library over an explicit set of directories, for a test or for a caller with a second place
     * worth searching. A root that does not exist is skipped, not an error.
     *
     * @param searchRoots the directories to scan, in precedence order (first match wins)
     * @param registry    resolves each module's node types so its interface can be derived
     * @return a library over those directories
     */
    public static ModuleLibrary over(List<Path> searchRoots, NodeRegistry registry) {
        return new ModuleLibrary(searchRoots, registry);
    }

    /** Drops the cached scan, so the next lookup re-reads the search roots. */
    public void refresh() {
        rootsById = null;
        filesById = null;
    }

    @Override
    public Optional<ModuleEntry> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        JSONObject root = index().get(id);
        return root == null ? Optional.empty() : Optional.of(entryFor(id, root, filesById.get(id)));
    }

    /**
     * The parsed root of the module with this id, or null when it is not in the index.
     *
     * <p>Shaped to be passed as {@code library::rootOf} where a
     * {@code GraphStructureValidator.ModuleResolver} is wanted — but deliberately not
     * {@code implements}, so that {@code catalog/} depends on this package and not the other way
     * round. The validator does no I/O; this is what a caller hands it when following a module
     * reference is acceptable.
     *
     * @param moduleId the module's stable id
     * @return its parsed root, or null
     */
    public JSONObject rootOf(String moduleId) {
        return moduleId == null || moduleId.isBlank() ? null : index().get(moduleId);
    }

    /**
     * Resolves a reference the way a consumer holds one: an id, plus the path it was last found at.
     *
     * <p>The hint is a shortcut, not an authority. It is read only when the scan did not already
     * find the id, and only accepted when the file it names actually carries that id — otherwise a
     * module deleted and replaced at the same path would be silently substituted for the one the
     * consumer meant.
     *
     * @param id       the module's stable id
     * @param pathHint where it was last found, or null/blank when nothing was recorded
     * @return the resolved module, or empty when neither the search roots nor the hint has it
     */
    public Optional<ModuleEntry> resolve(String id, String pathHint) {
        Optional<ModuleEntry> found = byId(id);
        if (found.isPresent() || id == null || id.isBlank() || pathHint == null || pathHint.isBlank()) {
            return found;
        }
        Path hinted = Path.of(pathHint);
        if (!Files.isRegularFile(hinted)) {
            return Optional.empty();
        }
        JSONObject root = read(hinted);
        if (root == null || !id.equals(ModuleFile.idOf(root))) {
            return Optional.empty();
        }
        return Optional.of(entryFor(id, root, hinted));
    }

    /**
     * Gives a graph file an identity as a module and adds it to the index.
     *
     * <p><b>This is where a module id is assigned.</b> Scanning deliberately does not mint one: a
     * read of the modules directory must not rewrite the files in it, and "this graph is now a
     * module other graphs may reference" is a decision, not a side effect of looking. The file is
     * rewritten only when an id was actually added.
     *
     * @param file the graph file to publish
     * @return the module it now is
     * @throws IOException              if the file cannot be read or written
     * @throws IllegalArgumentException if the graph declares no boundary markers, so has no interface
     */
    public ModuleEntry publish(Path file) throws IOException {
        JSONObject root = GraphFileIO.readRoot(file.toFile());
        if (!ModuleFile.isModule(root)) {
            throw new IllegalArgumentException(
                    "This graph declares no module boundary markers, so it has no interface to publish: " + file);
        }
        String before = ModuleFile.idOf(root);
        String id = ModuleFile.ensureId(root);
        if (before == null) {
            Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        }
        ModuleEntry entry = entryFor(id, root, file);
        index().put(id, root);
        filesById.put(id, file);
        return entry;
    }

    /** Derives everything a consumer wants to know about one already-parsed module root. */
    private ModuleEntry entryFor(String id, JSONObject root, Path file) {
        String declared = ModuleFile.nameOf(root);
        String name = declared != null ? declared
                : file != null ? stripExtension(file.getFileName().toString())
                : id;
        return new ModuleEntry(id, name, file == null ? "" : file.toAbsolutePath().toString(),
                ModuleInterface.derive(root, registry), GraphDependencyCheck.requiredBy(root));
    }

    /** The cached id -> root index, scanning the search roots on first use. */
    private Map<String, JSONObject> index() {
        if (rootsById != null) {
            return rootsById;
        }
        Map<String, JSONObject> roots = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        for (Path searchRoot : searchRoots) {
            for (Path file : jsonFilesIn(searchRoot)) {
                JSONObject root = read(file);
                if (root == null) {
                    continue;
                }
                String id = ModuleFile.idOf(root);
                if (id == null) {
                    // Not a defect: an ordinary graph parked here is simply not a module until it is
                    // published. Debug rather than warn, or the log fills up with every save.
                    log.debug("{} carries no module id, so nothing can reference it; publish it first", file);
                    continue;
                }
                if (roots.putIfAbsent(id, root) == null) {
                    files.put(id, file);
                } else {
                    log.warn("Two module files claim the id {}; keeping {} and ignoring {}", id, files.get(id), file);
                }
            }
        }
        rootsById = roots;
        filesById = files;
        return rootsById;
    }

    private static List<Path> jsonFilesIn(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = new ArrayList<>(entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList());
            // Files.list makes no ordering promise, and which of two files claiming one id wins has
            // to be the same on every machine.
            files.sort(Path::compareTo);
            return files;
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not list the module directory {}: {}", directory, e.toString());
            return List.of();
        }
    }

    private static JSONObject read(Path file) {
        try {
            return GraphFileIO.readRoot(new File(file.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping {}: it could not be read as a graph ({})", file, e.toString());
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
