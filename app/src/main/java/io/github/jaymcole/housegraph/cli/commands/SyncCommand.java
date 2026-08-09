package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.remote.GraphRepository;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.remote.RemoteDeployment;
import io.github.jaymcole.housegraph.remote.RemoteState;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pulls every configured repository once and says what changed, without starting anything.
 *
 * <p>The daemon's first half on its own. Useful for seeing whether credentials, branch names and the
 * manifest are right before handing the machine over to a supervisor that would otherwise report
 * those failures only in a log file.
 */
public final class SyncCommand implements Command {

    private final PrintStream out;

    public SyncCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String summary() {
        return "Pull the configured graph repositories now and report what changed";
    }

    @Override
    public String usage() {
        return "  sync [--force]\n\n"
                + "--force syncs even when the remote hasn't moved, to repair a damaged local mirror.\n"
                + "Reads config/remote.json. Starts nothing — use `housegraph daemon` for that.";
    }

    @Override
    public int run(Args args) {
        RemoteConfig config = RemoteConfig.load();
        if (config.repositories().isEmpty()) {
            out.println("No repositories configured in config/remote.json.");
            out.println("Run `housegraph doctor` for where that file goes and what it holds.");
            return 2;
        }

        RemoteDeployment deployment = new RemoteDeployment(config, RemoteState.load());
        boolean force = args.isEnabled("force");
        int failures = 0;

        for (GraphRepository repository : deployment.repositories()) {
            Optional<RemoteDeployment.Deployment> result = deployment.refresh(repository, force);
            if (result.isEmpty()) {
                out.println(repository.config().url() + ": FAILED (see the log for why)");
                failures++;
                continue;
            }
            RemoteDeployment.Deployment current = result.get();
            out.println(repository.config().url() + ": "
                    + (current.changed() ? "updated to " : "already at ")
                    + current.sha().substring(0, Math.min(7, current.sha().length()))
                    + ", " + current.graphs().size() + " graph(s)");
            for (Path graph : current.graphs()) {
                out.println("    " + repository.cloneDirectory().relativize(graph));
            }
        }
        return failures == 0 ? 0 : 1;
    }
}
