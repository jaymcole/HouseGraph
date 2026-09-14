# Storage, secrets and preferences

The `storage/` package owns everything that touches the user's disk. Two rules run
through it:

- **All on-disk paths go through `AppDirectories`.** Never hardcode a home
  directory or an OS-specific location.
- **Credentials live only in `SecretsStore`, encrypted.** They never enter a save
  file, a plaintext config, or any registry a node writes.

## `AppDirectories`

The single source of truth for on-disk locations. One root per platform:

- **Windows:** `%APPDATA%\HouseGraph`
- **macOS:** `~/Library/Application Support/HouseGraph`
- **Linux/other:** `$XDG_DATA_HOME/HouseGraph`, else `~/.local/share/HouseGraph`

Under it, a subdirectory per purpose, each created on demand: `secrets()`,
`nodes()` (plus `nodeStorage(key)`), `plugins()` (plus `pluginJar(id, version)`),
`saves()`, `modules()`, `remotes()` (plus `remoteRepo(key)`), `dataStores()` (plus
`dataStore(name)`), `config()`, `cache()`, `logs()`.

Every key is sanitised so it cannot escape its folder.

- Use the shared instance: `AppDirectories.get().secrets()`.
- The root can be overridden with the `housegraph.home` system property or the
  `HOUSEGRAPH_HOME` environment variable — useful for a portable install and
  essential for tests, which point it at a temp directory.
- `resolveRoot(...)` is pure, with no filesystem access, so each OS branch is
  unit-testable.

### Three easily-confused pairs

**`nodes()` vs `plugins()`** mean opposite things. `nodes()` is a node's *private
runtime storage*; `plugins()` holds *installed node-library code*, the jars
downloaded from external repositories.

`pluginJar(id, version)` resolves `plugins/<id>/<version>/<id>.jar`, and the version
segment is not cosmetic. The class loader serving a library's classes holds an open
handle on its jar, and on Windows an open jar can be neither deleted nor
overwritten, so an update installs to a **new** path rather than replacing one in
use. Superseded versions are pruned at the next startup, before any loader exists.

**`saves()` vs `remotes()`.** `saves()` holds graphs authored on this machine.
Everything under `remotes()` is a **git mirror**, overwritten wholesale on every
sync, so nothing put there by hand survives. The key is derived from the repository
URL rather than chosen, so the same remote always maps to the same directory across
restarts. See [remote-runtime.md](remote-runtime.md).

**`saves()` vs `modules()`.** Both hold ordinary graph files, and the difference is
who reads them. `saves()` is where a user keeps their own work, and is only a
starting directory for the file dialog — nothing searches it. `modules()` is the one
place a **module reference is resolved**: `ModuleLibrary` scans it for `*.json` and
indexes each file by the stable `module.id` in its root, so a module found there
keeps working when its file is moved or renamed. The id is what identifies a module;
a path is only a hint. See
[`../decisions/0011-modules-are-referenced-by-id.md`](../decisions/0011-modules-are-referenced-by-id.md).

Publishing a graph as a module (`ModuleLibrary.publish`) is the only thing that
assigns an id, and the only thing here that writes into a file it was handed.
Scanning never does: a read of this directory must not rewrite the files in it.

### Runtime data written outside the save

A data-store node persists its JSON document to `dataStore(<name>)/document.json`,
keyed by a **user-chosen name rather than an opaque id**. That makes the data
recoverable: deleting the node and recreating one with the same name reopens the
existing document, instead of stranding it under an id nobody can retype. Renaming
points the node at a different store; the old one stays on disk under its name and
returns if the name is typed again.

This follows the same discipline as computed values: the `.json` save holds
authored config, not the store's contents.

## `SecretsStore`

A key/value store encrypted at rest with **AES-256-GCM**.

A random 256-bit key is generated once and kept in `secret.key` alongside the
encrypted `secrets.enc`, both under `AppDirectories.secrets()`. The data blob is
`[12-byte IV][ciphertext + 16-byte GCM tag]`, with a fresh random IV per save. GCM
authenticates every read, so a tampered or truncated file fails with a
`SecretsException` rather than yielding garbage.

**Threat model.** This keeps secrets out of plaintext on disk and out of saved
graphs, and defends against casual inspection or an accidentally-synced file. It
does **not** defend against someone who can already read the secrets folder, since
the key lives there too. The on-disk format is designed so that a future password-
or keychain-derived key changes only *how the key is obtained*, not the format. See
[security-model.md](security-model.md).

API: `open()`, `keys()` (names only, never values), `get`/`put`/`remove`, and an
explicit `save()`. **Not thread-safe** — open, use and save on one thread.

### Keys the app itself owns

Almost every entry is a user's own, named by them. One is written by the app: the
Discord log webhook lives under **`log.discord.webhook`**, put there by the log
window's External… dialog. A webhook URL is a credential — anyone holding it can post
to the channel — so it belongs here and not in `preferences.json`, which is the same
rule a node follows. It shows up in `SecretsEditor` like any other entry; deleting it
there switches the destination off rather than leaving it failing every post. See
[logging.md](logging.md).

Edited through the `SecretsEditor` modal. Consumed by nodes that store the secret's
**key** and resolve the value at runtime; a node library does the same through the
published `sdk.Secrets` facade rather than this class directly.

### How secrets flow

Nodes never hold a secret value in a persisted field. They persist a *reference*
— the `SecretsStore` key, typically in `saveState` — mark any variable that briefly
holds the resolved value with `NodeVariable.markSecret()`, and read the real value
from the store only when needed. `.env` keys also seed the Secret Loader dropdown;
see `.env.example`.

## `AppPreferences`

A small persistent key/value store, plain JSON under `AppDirectories.config()`, for
non-sensitive UX state: the settings the preferences window edits, plus the state the
app remembers on its own.

**Reading is forgiving:** a missing or corrupt file yields empty preferences rather
than failing, so a bad prefs file can never stop the app starting. Writing is
explicit through `save()`.

Values are strings, so `getBoolean`/`getInt`/`getLong` parse on the way out. Each
takes the value to use when nothing is saved **and** when what is saved does not
parse — a hand-edited typo reads as the default rather than as `false` or `0`, which
is what `Boolean.parseBoolean` and `Integer.parseInt` would give.

### The keys

Settings — edited in Tools ▸ Settings…, modelled by `ui/settings/AppSettings`:

| Key | Holds |
| --- | --- |
| `graph.folder` | the chosen graph folder; absent means the built-in default |
| `graph.rememberLastFolder` | whether a graph dialog reopens where the last one left off |
| `startup.reopenLastGraph` | whether a bare launch reopens `lastFile` |
| `window.restoreSize` | whether a new editor window takes the remembered size |
| `recentFiles.cap` | how many entries Open Recent keeps |
| `run.defaultStepDelayMillis` | the Watch Speed a window starts at |
| `log.file.maxBytes`, `log.file.maxBackups` | the log file's rotation policy |
| `log.buffer.capacity` | records retained for the log window |
| `log.window.autoScroll` | whether the log window follows new records |
| `plugin.skipInstallWarning` | whether the node-library install warning is suppressed |

State — written by the app as you work, not edited directly:

| Key | Holds |
| --- | --- |
| `lastFile` | absolute path of the most recently saved/opened graph |
| `recentFiles` | JSON array of recent absolute paths, newest first |
| `graph.lastFolder` | the folder a graph was last opened from or saved to |
| `window.width`, `window.height` | the size of the last editor window closed |
| `log.level.<sink>` | each log output's chosen level |
| `log.discord.enabled` | whether the external log destination is switched on |

### Settings apply to the running app

A setting is pushed onto the thing that implements it the moment it is saved, not at
the next launch. `AppSettings.applyGlobally()` covers the process-wide half — the
graph folder on `AppDirectories`, the rotation policy on the live `FileSink`, the
ring size on the shared `LogBufferSink` — and `App.applySettings` covers the
per-window half. A setting added without a home in one of those two is a setting that
silently does nothing until restart.

`startup.reopenLastGraph` and `window.restoreSize` are the exception, and not a gap:
they describe what happens *during* startup, which has already happened by the time
they can be edited.

### The graph folder

`AppDirectories.saves()` is the **only** directory that can be moved out from under
the root, through `setSaves`. Graphs are the user's documents and belong wherever they
keep documents; everything else under the root is the app's own state. Moving them
with `HOUSEGRAPH_HOME` instead would drag the encrypted secret key, the plugin jars
and the logs along with them.

The override goes *through* `AppDirectories` rather than being a path the UI computes
for itself, which is what keeps the "all on-disk paths go through `AppDirectories`"
invariant true. A folder that cannot be created is reported while the picker is still
open; one that has gone away since — an unplugged drive — logs a warning at startup
and falls back to the default for that session, leaving the preference alone so it
comes back when the drive does.

An instance launched with `--graph`, meaning a supervised one, **does not write
`LAST_FILE`**, touch the recent list, or record its window size. Settings are
unaffected — those are the user's choices, not a record of the session. It would otherwise overwrite whatever the
person at the keyboard had open, and on a machine running several graphs there is no
single "last" file to record.

### The recent-files list

`ui/io/RecentGraphs` owns it: absolute paths, newest first, deduplicated and capped
at `recentFiles.cap` (ten by default). The store is string-valued, so the list is
encoded as a JSON array inside the one `recentFiles` value rather than getting a file
of its own — a handful of paths is not structured state. An unreadable value reads
back as an empty list, matching the store around it.

The cap is read on every load rather than captured, so lowering it shortens the list
at the next read — which is the next time the Open Recent submenu opens, since that
menu rebuilds itself each time it is shown.

**Entries are never pruned for being absent.** A graph on an unplugged drive would
otherwise be forgotten by the one launch that happened while it was away; the menu
greys it out instead, and it returns when the path does.

The glue lives in the UI layer, like `LogLevelPreferences`, because that is the layer
that knows about both the store and the thing being remembered.

## Files under `config()`

`AppPreferences` is string-values-only, so anything structured gets its own file
beside it, each written atomically and read forgivingly:

| File | Holds | Owner |
| --- | --- | --- |
| `preferences.json` | flat string preferences | `AppPreferences` |
| `plugins.json` | installed node libraries | `PluginCatalog` |
| `remote.json` | tracked git repositories, and what may be auto-installed | `RemoteConfig` |
| `remote-state.json` | last commit deployed per repository | `RemoteState` |

`remote.json` is the one file here a user is expected to **write by hand**, and it
is the trust boundary for unattended installs. A git token for a private repository
is named there by its `SecretsStore` key, never written into it.

## `SecretsException`

Unchecked exception for crypto and secret-store failures — bad key, corrupt or
tampered file. Distinct from the `UncheckedIOException` used for plain I/O.

---

**When you change this, update…** this file and the relevant Javadoc whenever you
change the directory layout, the encryption scheme or its threat model, the
preferences format, **the set of preference keys or which of them is a setting**, the
set of files under `config()`, or the rule about what may
be written in plaintext.
