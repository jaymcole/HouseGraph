package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.AppVersion;
import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.remote.GitCommand;
import io.github.jaymcole.housegraph.remote.GraphProcess;
import io.github.jaymcole.housegraph.remote.GraphRepository;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.remote.RemoteState;
import io.github.jaymcole.housegraph.remote.SelfUpdater;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reports whether this machine is set up to run graphs unattended, and says what to fix when it
 * isn't.
 *
 * <p>Worth its own command because every prerequisite here fails at a different, unhelpful moment
 * otherwise: no git binary surfaces as a failed sync an hour later, no {@code remote.json} as a
 * daemon that starts and does nothing, and running from exploded classes as a supervisor that can't
 * name its own jar. Checking them together, on demand, turns a support conversation into one
 * command.
 */
public final class DoctorCommand implements Command {

    private final PrintStream out;

    public DoctorCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String summary() {
        return "Check this machine is ready to run graphs unattended";
    }

    @Override
    public int run(Args args) {
        boolean healthy = true;

        AppDirectories directories = AppDirectories.get();
        out.println("HouseGraph:      " + AppVersion.describe());
        out.println("Data directory:  " + directories.root());

        boolean git = GitCommand.isAvailable();
        out.println("git:             " + (git ? "found" : "MISSING"));
        if (!git) {
            out.println("                 Install the Xcode command line tools: xcode-select --install");
            healthy = false;
        }

        Path jar = GraphProcess.runningJar();
        out.println("Running jar:     " + (jar != null ? jar : "not a jar (daemon needs one)"));
        if (jar == null) {
            out.println("                 Build one with ./gradlew :app:shadowJar, then run that jar.");
            healthy = false;
        }

        Path configFile = directories.config().resolve("remote.json");
        RemoteConfig config = RemoteConfig.load();
        out.println("remote.json:     " + configFile);
        if (config.repositories().isEmpty()) {
            out.println("                 No repositories configured — the daemon would have nothing to do.");
            out.println("                 See docs/guides/server-setup.md for the file's shape.");
            healthy = false;
        } else {
            out.println("Poll interval:   " + config.pollSeconds() + "s");
            boolean anyReachableOverSsh = false;
            for (RemoteConfig.Repository repository : config.repositories()) {
                boolean reachable = reportRepository(repository);
                healthy &= reachable;
                anyReachableOverSsh |= reachable && isSsh(repository.url());
            }
            if (anyReachableOverSsh && System.getenv("SSH_AUTH_SOCK") != null) {
                out.println("                 Note: this shell has an ssh-agent that a LaunchAgent or");
                out.println("                 systemd unit will not, so a repository reachable here can");
                out.println("                 still be unreachable to the daemon. If it cannot sync, use a");
                out.println("                 passphrase-less deploy key, or set GIT_SSH_COMMAND in the");
                out.println("                 supervisor's configuration to name the key.");
            }
        }

        out.println("Plugin installs: " + describeInstallGate(config));

        out.println("Self-update:     " + describeSelfUpdate(config));
        if (config.selfUpdate().enabled()) {
            Optional<String> blocked = new SelfUpdater(config.selfUpdate(), RemoteState.load()).canApply();
            if (blocked.isPresent()) {
                out.println("                 It is on, but this machine cannot apply one:");
                out.println("                 " + blocked.get());
                healthy = false;
            }
        }

        PluginCatalog catalog = PluginCatalog.load();
        out.println("Node libraries:  " + catalog.all().size() + " installed, "
                + catalog.enabled().size() + " enabled");
        catalog.all().forEach(installed -> out.println("                 " + installed.id() + " "
                + installed.version() + (installed.enabled() ? "" : " (disabled)")));

        out.println();
        out.println(healthy ? "Ready." : "Not ready — see the notes above.");
        return healthy ? 0 : 1;
    }

    /**
     * Asks one repository's remote whether it can be reached, and says so.
     *
     * <h4>Why this belongs in doctor at all</h4>
     * Every other prerequisite here fails loudly. An unreachable graphs repository fails
     * <em>quietly</em>: the daemon starts, reports itself healthy, polls, and logs the refusal at
     * one line a minute into a file nobody is tailing — while no graph has ever been deployed. The
     * only visible symptom is that nothing happens, which is indistinguishable from a graph saved
     * with its trigger stopped. One {@code ls-remote} here tells the operator which.
     *
     * @param repository the configured repository
     * @return true when its branch head could be read
     */
    private boolean reportRepository(RemoteConfig.Repository repository) {
        String label = "                 " + repository.url() + " (" + repository.branch() + ") — ";
        try {
            Optional<String> head = new GraphRepository(repository).remoteHead();
            if (head.isEmpty()) {
                out.println(label + "reachable, but has no branch \"" + repository.branch() + "\"");
                return false;
            }
            out.println(label + "reachable, at " + head.get().substring(0, Math.min(7, head.get().length())));
            return true;
        } catch (RuntimeException e) {
            out.println(label + "UNREACHABLE");
            reason(e).forEach(line -> out.println("                   " + line));
            return false;
        }
    }

    /**
     * The first couple of lines of a git failure. Git says the useful part first — "Permission
     * denied (publickey)" — and then several lines of advice; printing all of it buries the rest of
     * the report.
     */
    private static List<String> reason(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return message.lines().map(String::strip).filter(line -> !line.isBlank()).limit(2).toList();
    }

    /** Whether this URL authenticates over ssh, and so depends on a key rather than a token. */
    private static boolean isSsh(String url) {
        return url.startsWith("git@") || url.startsWith("ssh://");
    }

    /**
     * Whether this machine keeps HouseGraph itself up to date, and from where.
     *
     * <p>Named even when it is off, because "why is this server still on an old build?" and "why did
     * this server change under me?" are both questions this line answers before they are asked.
     */
    private static String describeSelfUpdate(RemoteConfig config) {
        RemoteConfig.SelfUpdate selfUpdate = config.selfUpdate();
        if (!selfUpdate.enabled()) {
            return "off — `housegraph update` applies one by hand";
        }
        return "every " + selfUpdate.checkSeconds() + "s from " + selfUpdate.repository();
    }

    /**
     * Spells out the install gate in the terms an operator would ask about, because "why did my graph
     * come up with placeholder nodes?" is the main question this command exists to answer.
     *
     * <p>The empty-allowlist case gets its own wording rather than reading "0 trusted repositories",
     * which would suggest the opposite of what it does: empty means <em>no narrowing</em>, so any
     * GitHub repository the synced graphs name may be installed from.
     */
    private static String describeInstallGate(RemoteConfig config) {
        if (!config.allowPluginInstall()) {
            return "off (graphs and manifests can't install libraries)";
        }
        return config.trustedPluginRepositories().isEmpty()
                ? "allowed from any GitHub repository your graphs name"
                : "allowed from " + config.trustedPluginRepositories().size() + " trusted repository/ies";
    }
}
