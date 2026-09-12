package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.AppVersion;
import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.remote.RemoteConfig;
import io.github.jaymcole.housegraph.remote.RemoteState;
import io.github.jaymcole.housegraph.remote.SelfUpdater;

import java.io.PrintStream;

/**
 * Updates HouseGraph itself from its GitHub releases, or says what an update would be.
 *
 * <h2>Why it exists next to the daemon's automatic one</h2>
 * The same code path, driven by a person. That matters twice over: an operator who leaves
 * {@code selfUpdate.enabled} off — the default — still wants the one-command upgrade rather than the
 * four-step build in the runbook, and anyone turning the automatic one on wants to see what it will
 * do before it does it unattended, which is what {@code --check} is for.
 *
 * <p>Deliberately not gated on {@code selfUpdate.enabled}: that setting says whether the <em>daemon</em>
 * updates on its own, and someone typing this command has already decided. The repository it takes
 * releases from is still the one named in {@code remote.json}.
 */
public final class UpdateCommand implements Command {

    private final PrintStream out;

    public UpdateCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String summary() {
        return "Update HouseGraph itself to the latest GitHub release";
    }

    @Override
    public String usage() {
        return "  update [--check]\n\n"
                + "--check reports what is available and installs nothing.\n\n"
                + "Replaces the jar this command is running from, keeping the old one as\n"
                + "<jar>.previous, and verifies the downloaded jar starts before installing it.\n"
                + "A running daemon keeps its current build until it is restarted.\n"
                + "Set selfUpdate.enabled in config/remote.json to have the daemon do this itself.";
    }

    @Override
    public int run(Args args) {
        RemoteConfig.SelfUpdate config = RemoteConfig.load().selfUpdate();
        SelfUpdater updater = new SelfUpdater(config, RemoteState.load());

        out.println("Installed:  " + AppVersion.describe());
        updater.targetJar().ifPresent(jar -> out.println("Jar:        " + jar));
        out.println("Releases:   " + config.repository());

        SelfUpdater.Decision decision = updater.check();
        out.println(decision.message());

        if (!decision.isUpdate()) {
            // Being up to date is the answer, not a failure; everything else here is something the
            // operator has to act on, so it gets a non-zero code a script can branch on.
            return switch (decision.action()) {
                case UP_TO_DATE, ALREADY_APPLIED -> 0;
                default -> 1;
            };
        }
        if (args.isEnabled("check")) {
            out.println("Run `housegraph update` to install it.");
            return 0;
        }
        if (!updater.apply(decision)) {
            out.println("The update was not installed — see the log for why.");
            return 1;
        }
        out.println("Installed " + decision.version() + ". Restart HouseGraph to run it;");
        out.println("a supervised daemon picks it up on its next restart.");
        return 0;
    }
}
