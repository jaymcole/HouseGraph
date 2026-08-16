# Security model

This document states what HouseGraph defends against and what it does not. It is
deliberately narrow: claiming a boundary that does not exist is worse than having
none.

## A node library is arbitrary code

**A node library runs in this JVM with the user's full privileges.** It can read
the secrets store, the filesystem and the network directly.

There is no sandbox. `SecurityManager` is deprecated for removal and unusable on
Java 21+, JPMS carries no permission model, and running nodes out-of-process would
break the `NodeContentProvider` design, since a node supplies its inline UI by
returning a live `javafx.scene.Node`.

So **installing a node library is exactly as dangerous as running any program you
downloaded.** The install dialog and the README say so plainly, and should keep
saying so.

## What is enforced

| Control | Where | Effect |
| --- | --- | --- |
| Explicit install confirmation before any download | `PluginWindow.confirmThenInstall` (one asset, from the Add-from-URL table) and `confirmThenUpdateAll` (a batch of updates, one dialog listing all of them) | Names what's about to be fetched and warns that a node library runs with full privileges. Update's batch confirmation always shows; the single-install warning has a "don't show this again" checkbox (`AppPreferences` key `plugin.skipInstallWarning`) once the user has seen and accepted it |
| Fetch origins restricted to GitHub | `GitHubReleases.ALLOWED_HOSTS`, re-checked in `PluginInstaller.download` | Neither a lookup nor a download can leave `github.com`, `api.github.com`, `objects.githubusercontent.com` |
| Save-file path containment | `RemoteDeployment` | A manifest's `graphs[].file` is resolved and verified to stay inside the clone; `../` and absolute paths are refused rather than clamped |
| Directory-key sanitising | `AppDirectories` | A hostile repository URL or manifest cannot write outside its own folder |
| Secret access through a seam | `sdk.Secrets` | Does nothing today that `SecretsStore.open()` does not; exists so a per-library grant can be added host-side later |

There is deliberately no "remember this repository" in the app — the dismissible
warning is a global one-time acknowledgement of what installing means at all, not
a per-repository trust decision, and every install still shows what it is about to
fetch in the Add-from-URL table itself before the "Add" click that triggers it. The
only place installing-without-asking makes sense is unattended.

## What is not enforced

**Cached jars are not verified on load.** `PluginInstaller` computes and records a
SHA-256 at install time and `matchesRecordedHash` exists, but `PluginLoader` loads
whatever jar is on disk. A swapped cached jar is not noticed. Do not describe the
cached jars as verified.

**`SecretsStore` does not defend against a local reader.** The encryption key sits
beside the ciphertext, so the store keeps secrets out of save files and off disk in
plaintext, and defends against casual inspection or an accidentally-synced file —
not against someone who can already read the secrets folder. See
[storage.md](storage.md).

Node libraries do not worsen the local-attacker story. They create a **remote
exfiltration** story that did not exist before, which is what the install
confirmation and origin restriction address.

## The two trust boundaries

### A save file is untrusted input

A save file opened in the desktop app may have arrived from anywhere — emailed,
downloaded, opened out of curiosity. It may **propose** a code download, through
the repository URL in its `plugins` table, but must never **cause** one.

**The desktop app never auto-installs.** An install offered from a save file goes
through the same per-repository confirmation as any other.

### A named repository is a human decision

The unattended daemon does auto-install, and the asymmetry is the design rather
than an unfinished corner.

A save file the daemon runs is a commit in a repository the operator **hand-wrote
into `config/remote.json`**. Naming that repository is the trust decision, and it
has already been made by a human, in a file on their own machine, before anything
is fetched. Anyone able to commit to that repository can make the daemon run
arbitrary graphs regardless.

One gate controls it:

| Key | Means | Default |
| --- | --- | --- |
| `allowPluginInstall` | may this machine install node libraries at all | **false** |
| `trustedPluginRepositories` | *optional* narrowing to specific repositories | empty = no narrowing |

An empty allowlist means "no narrowing", not "nothing". Requiring the operator to
enumerate every node library on top of naming the graph repository was ceremony
rather than a boundary. `RemoteConfig.load` warns when installs are on with an empty
list, and `doctor` reports "allowed from any GitHub repository your graphs name",
so the wider meaning is never silent. `GitHubReleases.ALLOWED_HOSTS` still bounds
every fetch.

Two things the daemon never does:

- **A disabled library is never re-enabled.** Disabling is an explicit choice in
  the catalog, and unattended there is nobody to notice it being overturned.
- **Permission does not override the host allowlist.**
  `RequiredPlugin.isInstallable()` still has to pass, so a permitted repository
  that is not on GitHub is refused anyway.

Every refusal is logged with the reason and the fix, through
`RemoteDeployment.explainRefusal`.

## Credentials

**Prefer an SSH deploy key** for a graphs repository. With an SSH URL HouseGraph
never sees a credential; the key is the user's, handled by their agent.

For HTTPS, a token is named by its `SecretsStore` key in `remote.json` and passed
to git through `GIT_ASKPASS` and the child's **environment**, never in the remote
URL — `argv` is readable by every process on the machine, a child's environment is
not. `GIT_TERMINAL_PROMPT=0` goes with it, so a wrong token fails instead of
blocking forever on a prompt nobody will answer.

## Process isolation, and its limit

Graphs run as child processes of the daemon, one per graph, so a graph that wedges
takes only itself down. But every graph runs **as the operator**, with their full
privileges and access to their secrets store. Treat the graphs repository as
something only you can push to, and keep the deploy key read-only.

---

**When you change this, update…** this file whenever you change what is verified,
the fetch restrictions, the unattended trust gates, or the credential handling.
Never soften a statement here without changing the code first.
