package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.remote.GitCommand;
import io.github.jaymcole.housegraph.remote.GraphProcess;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.storage.AppDirectories;

import java.io.PrintStream;
import java.nio.file.Path;

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
            out.println("                 See docs/architecture/deployment.md for the file's shape.");
            healthy = false;
        } else {
            config.repositories().forEach(repository ->
                    out.println("                 " + repository.url() + " (" + repository.branch() + ")"));
            out.println("Poll interval:   " + config.pollSeconds() + "s");
        }

        out.println("Plugin installs: " + (config.allowPluginInstall()
                ? "allowed from " + config.trustedPluginRepositories().size() + " trusted repository/ies"
                : "off (manifests can't install libraries)"));

        PluginCatalog catalog = PluginCatalog.load();
        out.println("Node libraries:  " + catalog.all().size() + " installed, "
                + catalog.enabled().size() + " enabled");
        catalog.all().forEach(installed -> out.println("                 " + installed.id() + " "
                + installed.version() + (installed.enabled() ? "" : " (disabled)")));

        out.println();
        out.println(healthy ? "Ready." : "Not ready — see the notes above.");
        return healthy ? 0 : 1;
    }
}
