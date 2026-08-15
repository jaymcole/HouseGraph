package io.github.jaymcole.housegraph.cli.commands;

import io.github.jaymcole.housegraph.cli.Args;
import io.github.jaymcole.housegraph.cli.Command;
import io.github.jaymcole.housegraph.plugin.GitHubReleases;
import io.github.jaymcole.housegraph.plugin.PluginCatalog;
import io.github.jaymcole.housegraph.plugin.PluginInstaller;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * Manages installed node libraries from the terminal: list, install, update.
 *
 * <p>Possible with no new machinery because the whole plugin runtime was already headless — this
 * command is a thin front end over the same {@code PluginCatalog} and {@code PluginInstaller} the
 * library window drives. Which is the point of that design: the window is a shell, and everything
 * worth running lives outside it.
 *
 * <p>Installing here is an explicit act by whoever is typing, so it needs no allowlist — that gate
 * ({@code RemoteConfig.isTrustedForInstall}) exists for the daemon, where nobody is present to
 * judge. The GitHub-host restriction in {@code GitHubReleases} still applies to both.
 */
public final class PluginsCommand implements Command {

    private final PrintStream out;

    public PluginsCommand(PrintStream out) {
        this.out = out;
    }

    @Override
    public String name() {
        return "plugins";
    }

    @Override
    public String summary() {
        return "List, install or update node libraries";
    }

    @Override
    public String usage() {
        return "  plugins list\n"
                + "  plugins install <github-repository-url>\n"
                + "  plugins update [id...]        (all installed libraries when no id is given)\n\n"
                + "A library is arbitrary code running with your privileges. Install only what you trust.";
    }

    @Override
    public int run(Args args) {
        String action = args.positional(0).orElse("list");
        PluginCatalog catalog = PluginCatalog.load();
        return switch (action) {
            case "list" -> list(catalog);
            case "install" -> install(args, catalog);
            case "update" -> update(args, catalog);
            default -> {
                out.println("Unknown plugins action: " + action);
                out.println(usage());
                yield 2;
            }
        };
    }

    private int list(PluginCatalog catalog) {
        if (catalog.all().isEmpty()) {
            out.println("No node libraries installed.");
            return 0;
        }
        for (PluginCatalog.Installed installed : catalog.all()) {
            out.printf("%-28s %-10s %s%s%n",
                    installed.id(),
                    installed.version(),
                    installed.repository() == null ? "(no repository recorded)" : installed.repository(),
                    installed.enabled() ? "" : "  [disabled]");
        }
        return 0;
    }

    private int install(Args args, PluginCatalog catalog) {
        String url = args.positional(1).orElse(null);
        if (url == null) {
            out.println("Usage: housegraph plugins install <github-repository-url>");
            return 2;
        }
        try {
            GitHubReleases.Release release = GitHubReleases.latest(url, null)
                    .orElseThrow(() -> new IllegalStateException("No release information for " + url));
            if (release.hasSeveralLibraries()) {
                // A monorepo attaches a jar per library. Picking one arbitrarily would install the
                // wrong thing, so the choice is named rather than guessed.
                out.println("That release publishes several libraries; name the one you want:");
                release.assets().forEach(asset -> out.println("  " + asset.name()));
                out.println();
                out.println("  housegraph plugins install " + url + " --asset <name>");
                return unlessAssetNamed(args, release, url, catalog);
            }
            PluginCatalog.Installed installed = PluginInstaller.install(url, catalog);
            out.println("Installed " + installed.id() + " " + installed.version());
            out.println("Restart any running graph for it to take effect.");
            return 0;
        } catch (IOException | RuntimeException e) {
            out.println("Install failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** Completes a multi-library install when {@code --asset} named one; otherwise reports the need. */
    private int unlessAssetNamed(Args args, GitHubReleases.Release release, String url, PluginCatalog catalog)
            throws IOException, InterruptedException {
        Optional<String> wanted = args.option("asset");
        if (wanted.isEmpty()) {
            return 2;
        }
        Optional<GitHubReleases.Asset> asset = release.assets().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(wanted.get()))
                .findFirst();
        if (asset.isEmpty()) {
            out.println("No asset named " + wanted.get() + " in " + release.tagName());
            return 2;
        }
        PluginCatalog.Installed installed = PluginInstaller.install(url, release, asset.get(), catalog);
        out.println("Installed " + installed.id() + " " + installed.version());
        return 0;
    }

    private int update(Args args, PluginCatalog catalog) {
        List<PluginCatalog.Installed> targets = args.positionals().size() > 1
                ? catalog.all().stream().filter(i -> args.positionals().subList(1, args.positionals().size())
                        .contains(i.id())).toList()
                : catalog.all();
        if (targets.isEmpty()) {
            out.println("Nothing to update.");
            return 0;
        }

        int failures = 0;
        for (PluginCatalog.Installed installed : targets) {
            if (installed.repository() == null) {
                out.println(installed.id() + ": no repository recorded, can't update");
                failures++;
                continue;
            }
            try {
                // By id, not by repository alone: a monorepo release attaches a jar per library, and
                // the id-less overload refuses those outright — which used to make "plugins update"
                // fail for every first-party library.
                PluginCatalog.Installed updated =
                        PluginInstaller.install(installed.repository(), installed.id(), catalog);
                out.println(installed.version().equals(updated.version())
                        ? installed.id() + ": already at " + updated.version()
                        : installed.id() + ": " + installed.version() + " -> " + updated.version());
            } catch (IOException | RuntimeException e) {
                out.println(installed.id() + ": " + e.getMessage());
                failures++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
        return failures == 0 ? 0 : 1;
    }
}
