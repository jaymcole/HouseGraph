package io.github.jaymcole.housegraph.modules;

import io.github.jaymcole.housegraph.graph.NodeRegistry;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleEntryNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleExitNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleInputNode;
import io.github.jaymcole.housegraph.graph.nodes.module.ModuleOutputNode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Answers, from a parsed save file's root and nothing else: <b>is this a module, what is its id,
 * and what does it declare?</b>
 *
 * <h2>Why this is not in {@code saveformat/}</h2>
 * {@code GraphFileIO} converts a graph to JSON and back and has no opinion about what any node
 * <em>means</em>. "This graph is usable as a module" is a fact about the boundary node types, which
 * live in {@code graph/nodes/module/} — so it is answered here, where knowing about them is the
 * point, rather than by teaching the format layer to recognise four node types. The format layer
 * keeps only what it must: it writes and reads the {@code module} object this class defines, the
 * same way it writes and reads a {@code plugins} row without knowing what a library is.
 *
 * <h2>Every method is pure</h2>
 * Nothing here opens a file, and nothing derives an interface that needs one. {@link ModuleLibrary}
 * is where the I/O lives.
 *
 * <h2>The shape of the root object</h2>
 * <pre>{@code
 * "module": { "id": "6f1c…", "name": "Doorbell" }
 * }</pre>
 * The id is the identity — see {@code docs/decisions/0011-modules-are-referenced-by-id.md} for why
 * it is not a path. The name is a label; renaming a module does not restrand its consumers.
 */
public final class ModuleFile {

    /** The root key holding a module file's own identity. */
    public static final String MODULE_KEY = "module";

    /** The root key holding a <em>consumer's</em> table of the modules it references. */
    public static final String MODULES_KEY = "modules";

    /** The per-node key on a {@code ModuleNode} naming its row in {@link #MODULES_KEY}. */
    public static final String NODE_MODULE_KEY = "module";

    private static final String ID_KEY = "id";
    private static final String NAME_KEY = "name";

    /** The saved type ids of the four boundary markers — what makes an ordinary graph a module. */
    private static final Set<String> BOUNDARY_TYPE_IDS = Set.of(
            NodeRegistry.persistentTypeId(ModuleInputNode.class),
            NodeRegistry.persistentTypeId(ModuleOutputNode.class),
            NodeRegistry.persistentTypeId(ModuleEntryNode.class),
            NodeRegistry.persistentTypeId(ModuleExitNode.class));

    private ModuleFile() {
    }

    /**
     * Whether this graph is usable as a module: it already carries an identity, or it declares at
     * least one boundary marker.
     *
     * <p>Read from the saved {@code type} ids alone, with no class loaded, so a file can be
     * classified in the same pure pass that reads its {@code plugins} table.
     *
     * @param root a parsed save file
     * @return true if this graph declares a module interface or already has an id
     */
    public static boolean isModule(JSONObject root) {
        if (idOf(root) != null) {
            return true;
        }
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null) {
            return false;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && BOUNDARY_TYPE_IDS.contains(node.optString("type", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * This file's own module id, or null when it has none.
     *
     * @param root a parsed save file
     * @return the stable module id, or null
     */
    public static String idOf(JSONObject root) {
        JSONObject module = root.optJSONObject(MODULE_KEY);
        if (module == null) {
            return null;
        }
        String id = module.optString(ID_KEY, "").trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * This file's module name — a label, never an identity.
     *
     * @param root a parsed save file
     * @return the declared name, or null when it declares none
     */
    public static String nameOf(JSONObject root) {
        JSONObject module = root.optJSONObject(MODULE_KEY);
        if (module == null) {
            return null;
        }
        String name = module.optString(NAME_KEY, "").trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Gives this root a module id if it has none, and returns the id it now carries.
     *
     * <p><b>This is the only place an id is minted.</b> A random {@code UUID}, because the id has to
     * be unique across machines that have never met — the daemon syncs graphs between them (see
     * {@code docs/engine/remote-runtime.md}) — and nothing about a graph's contents is both stable
     * under editing and unique between authors.
     *
     * <p>Minting mutates {@code root}; the caller is what decides whether that reaches disk.
     * {@link ModuleLibrary#publish} is the deliberate act that does.
     *
     * @param root a parsed save file
     * @return the id this file carries, minted just now if it had none
     */
    public static String ensureId(JSONObject root) {
        String existing = idOf(root);
        if (existing != null) {
            return existing;
        }
        String minted = UUID.randomUUID().toString();
        JSONObject module = root.optJSONObject(MODULE_KEY);
        if (module == null) {
            module = new JSONObject();
            root.put(MODULE_KEY, module);
        }
        module.put(ID_KEY, minted);
        return minted;
    }

    /**
     * Sets this file's module name, creating the {@code module} object if needed. A blank name
     * removes the key rather than writing an empty string.
     *
     * @param root a parsed save file
     * @param name the label to record, or null/blank to clear it
     */
    public static void setName(JSONObject root, String name) {
        JSONObject module = root.optJSONObject(MODULE_KEY);
        if (name == null || name.isBlank()) {
            if (module != null) {
                module.remove(NAME_KEY);
            }
            return;
        }
        if (module == null) {
            module = new JSONObject();
            root.put(MODULE_KEY, module);
        }
        module.put(NAME_KEY, name);
    }

    /**
     * Copies a file's module identity onto the root about to overwrite it.
     *
     * <p>{@code toJson} builds a fresh root from a snapshot, and a graph's identity as a module is
     * not part of any snapshot — it belongs to the <em>file</em>, exactly as a camera state belongs
     * to a view. Without this, an ordinary File ▸ Save of a module would drop its id and strand
     * every consumer referencing it. {@code ui.io.GraphFileIO.save} calls this with the root it is
     * about to replace, which is the one place that knows which file that is.
     *
     * @param previous the parsed root currently on disk, or null when the file is new
     * @param rewritten the root about to be written in its place
     */
    public static void carryIdentity(JSONObject previous, JSONObject rewritten) {
        if (previous == null || rewritten == null) {
            return;
        }
        JSONObject module = previous.optJSONObject(MODULE_KEY);
        if (module != null) {
            rewritten.put(MODULE_KEY, new JSONObject(module.toString()));
        }
    }

    /**
     * The module ids a consumer's root says it references, in table order.
     *
     * <p>Read from the root {@code modules} table rather than from the nodes, because the table is
     * what the format guarantees can be read before a single node is built — the same property the
     * {@code plugins} table exists for.
     *
     * @param root a parsed save file
     * @return the referenced module ids, duplicates dropped, in table order
     */
    public static List<String> referencedIds(JSONObject root) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(rowIds(root)));
        return List.copyOf(ids);
    }

    /**
     * Every {@code modules[].id} in table order, duplicates kept and a malformed row reading as the
     * empty string, so an index into this list is also an index into the table — which is what lets
     * a caller turn a finding into an RFC 6901 pointer.
     *
     * @param root a parsed save file
     * @return one entry per row of the {@code modules} table, in table order
     */
    public static List<String> rowIds(JSONObject root) {
        List<String> ids = new ArrayList<>();
        JSONArray modules = root.optJSONArray(MODULES_KEY);
        if (modules == null) {
            return ids;
        }
        for (int i = 0; i < modules.length(); i++) {
            JSONObject row = modules.optJSONObject(i);
            ids.add(row == null ? "" : row.optString(ID_KEY, "").trim());
        }
        return ids;
    }
}
