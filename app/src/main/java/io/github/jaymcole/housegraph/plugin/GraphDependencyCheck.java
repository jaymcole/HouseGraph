package io.github.jaymcole.housegraph.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares the node libraries a save file says it needs against the ones installed, <b>before any
 * node is built or any class is loaded</b>.
 *
 * <p>That ordering is the point. The save format's root {@code plugins} table exists so this can be
 * a single pass over a parsed {@code JSONObject}: pure, fast, no I/O, no network, no class loading.
 * The user can be told what is missing — and offered the repository it comes from — before the
 * graph is half-built.
 *
 * <p><b>A save file is untrusted input.</b> The repository URLs it carries are a suggestion to
 * download and execute code, so a caller may offer to install from one but must never do it
 * silently. See {@code docs/engine/plugin-runtime.md}.
 */
public final class GraphDependencyCheck {

    /** One library a save file depends on, as that file described it. */
    public record RequiredPlugin(String id, String name, String version, String repository) {

        /** Whether an install can be offered without the user typing anything. */
        public boolean isInstallable() {
            return repository != null && !repository.isBlank() && GitHubReleases.isAllowed(repository);
        }

        /** What to show in a list: "Discord 0.3.1", falling back to the id. */
        public String label() {
            String shown = name == null || name.isBlank() ? id : name;
            return version == null || version.isBlank() ? shown : shown + " " + version;
        }
    }

    /**
     * @param missing        named by the file, not installed at all
     * @param disabled       installed but switched off, so their nodes would not resolve
     * @param olderThanSaved installed at an older version than the graph was saved against
     */
    public record DependencyReport(List<RequiredPlugin> missing,
                                   List<RequiredPlugin> disabled,
                                   List<RequiredPlugin> olderThanSaved) {

        public DependencyReport {
            missing = List.copyOf(missing);
            disabled = List.copyOf(disabled);
            olderThanSaved = List.copyOf(olderThanSaved);
        }

        /**
         * Whether the graph can be opened with every node resolving. An older-than-saved library is
         * <em>not</em> a blocker: its nodes still load, they may just lack a newer feature, so it is
         * worth mentioning but not worth interrupting for.
         */
        public boolean isSatisfied() {
            return missing.isEmpty() && disabled.isEmpty();
        }

        /** Everything that would stop a node resolving, for a single list in a dialog. */
        public List<RequiredPlugin> blocking() {
            List<RequiredPlugin> all = new ArrayList<>(missing);
            all.addAll(disabled);
            return all;
        }
    }

    private GraphDependencyCheck() {
    }

    /**
     * Inspects a parsed save file against what is installed.
     *
     * @param saveRoot  the parsed save file
     * @param installed the current catalog
     * @return what is missing, disabled, or out of date
     */
    public static DependencyReport inspect(JSONObject saveRoot, PluginCatalog installed) {
        return classify(requiredBy(saveRoot), installed);
    }

    /**
     * The libraries a save file names, without comparing them to anything.
     *
     * <p>Split from {@link #inspect} so a caller with <em>several</em> save files — the daemon,
     * looking at every graph a repository deploys — can gather across all of them and classify once,
     * rather than merging reports after the fact. Duplicates within one file are dropped, first
     * occurrence winning.
     *
     * <p>A v1 file has no {@code plugins} table and yields nothing, even when it does use an
     * uninstalled library's node. Those nodes still become placeholders and are preserved, but with
     * no repository recorded there is nothing to offer. The first save under v2 fixes it for good.
     *
     * @param saveRoot the parsed save file
     * @return what it says it needs, in file order
     */
    public static List<RequiredPlugin> requiredBy(JSONObject saveRoot) {
        List<RequiredPlugin> required = new ArrayList<>();
        JSONArray plugins = saveRoot.optJSONArray("plugins");
        if (plugins == null) {
            return required;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject row = plugins.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String id = row.optString("id", "").trim();
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            required.add(new RequiredPlugin(
                    id,
                    row.optString("name", id),
                    emptyToNull(row.optString("version", "")),
                    emptyToNull(row.optString("repository", ""))));
        }
        return required;
    }

    /**
     * Sorts a set of requirements against the catalog into missing, disabled and out of date.
     *
     * <p><b>The first entry for an id wins</b>, and callers rely on that to express precedence by
     * ordering: the daemon concatenates its manifest's declarations ahead of what its save files ask
     * for, so an explicit declaration beside the graphs beats a byproduct of whichever machine last
     * saved one.
     *
     * @param required  what is wanted, in precedence order
     * @param installed the current catalog
     * @return what is missing, disabled, or out of date
     */
    public static DependencyReport classify(Collection<RequiredPlugin> required, PluginCatalog installed) {
        List<RequiredPlugin> missing = new ArrayList<>();
        List<RequiredPlugin> disabled = new ArrayList<>();
        List<RequiredPlugin> older = new ArrayList<>();

        Set<String> seen = new LinkedHashSet<>();
        for (RequiredPlugin entry : required) {
            if (entry == null || entry.id() == null || entry.id().isBlank() || !seen.add(entry.id())) {
                continue;
            }
            PluginCatalog.Installed match = installed.byId(entry.id()).orElse(null);
            if (match == null) {
                missing.add(entry);
            } else if (!match.enabled()) {
                disabled.add(entry);
            } else if (isOlder(match.version(), entry.version())) {
                older.add(entry);
            }
        }
        return new DependencyReport(missing, disabled, older);
    }

    /**
     * Whether {@code installedVersion} is behind {@code savedVersion}, comparing dot-separated
     * numeric parts and ignoring anything it can't read as a number. Deliberately lenient: a version
     * scheme it doesn't understand should say nothing rather than raise a false alarm.
     *
     * <p>Public because the daemon needs the same comparison against a repository manifest's declared
     * version ({@code RemoteDeployment.installDeclaredPlugins}). The leniency matters more there — a
     * false positive would download a jar and restart a graph for no reason — which is why an
     * unparseable part stops the comparison rather than guessing at an ordering.
     *
     * @param installedVersion the version on this machine
     * @param savedVersion     the version being asked for
     * @return true only when the installed version is definitely behind
     */
    public static boolean isOlder(String installedVersion, String savedVersion) {
        if (installedVersion == null || savedVersion == null
                || installedVersion.isBlank() || savedVersion.isBlank()) {
            return false;
        }
        String[] a = installedVersion.split("[.+-]");
        String[] b = savedVersion.split("[.+-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            Integer left = numberAt(a, i);
            Integer right = numberAt(b, i);
            if (left == null || right == null) {
                return false; // not comparable from here on; say nothing
            }
            if (!left.equals(right)) {
                return left < right;
            }
        }
        return false;
    }

    private static Integer numberAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
