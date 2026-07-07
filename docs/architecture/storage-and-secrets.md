# Storage & Secrets

The `storage/` package owns everything that touches the user's disk: where files
live, how secrets are encrypted, and non-sensitive preferences. Two hard rules
run through it:

- **All on-disk paths go through `AppDirectories`.** Never hardcode a home
  directory or an OS-specific location.
- **Credentials live only in `SecretsStore` (encrypted).** They never enter a
  save file, the camera registry, or any plaintext config.

## `AppDirectories` — where files live

The single source of truth for on-disk locations. One root per platform:

- **Windows:** `%APPDATA%\HouseGraph`
- **macOS:** `~/Library/Application Support/HouseGraph`
- **Linux/other:** `$XDG_DATA_HOME/HouseGraph`, else `~/.local/share/HouseGraph`

Under it, a subdirectory per purpose, each created on demand: `secrets()`,
`nodes()` (+ `nodeStorage(key)` for per-node private storage, with the key
sanitized so it can't escape the folder), `saves()`, `config()`, `cache()`.

- Use the shared instance: `AppDirectories.get().secrets()`, etc.
- The root can be overridden with the `housegraph.home` system property or the
  `HOUSEGRAPH_HOME` env var — handy for a portable install and essential for
  tests (which point it at a temp dir).
- `resolveRoot(...)` is pure (no filesystem access) so each OS branch is
  unit-testable.

## `SecretsStore` — encrypted key/value secrets

A key/value store encrypted at rest with **AES-256-GCM**.

- A random 256-bit key is generated once and kept in `secret.key` alongside the
  encrypted `secrets.enc`, both under `AppDirectories.secrets()`. File layout of
  the data blob: `[12-byte IV][ciphertext + 16-byte GCM tag]`, fresh random IV per
  save. GCM authenticates every read — a tampered/truncated file fails with a
  `SecretsException` rather than yielding garbage.
- **Threat model (be honest about it):** this keeps secrets off disk in plaintext
  and out of saved graphs, and defends against casual inspection or an
  accidentally-synced file — **but not** against someone who can already read the
  secrets folder, since the key lives there too. The on-disk format is designed so
  a future password- or OS-keychain-derived key only changes *how the key is
  obtained*, not the format.
- API: `open()`, `keys()` (names only, never values), `get`/`put`/`remove`,
  `save()` (explicit). Not thread-safe — open, use, and save on one thread.
- Edited via the `SecretsEditor` modal (`ui/`); consumed by nodes like the Secret
  Loader and Discord bot, which store the secret's **key** and resolve the value
  at runtime.

### How secrets flow through the app

Nodes never hold a secret value in a persisted field. They persist a *reference*
(the `SecretsStore` key, e.g. in `saveState`), mark any variable that briefly
holds the resolved value with `NodeVariable.markSecret()`, and read the real value
from `SecretsStore.open().get(key)` only when needed. `.env` keys also seed the
Secret Loader dropdown (see `.env.example`).

## `AppPreferences` — non-sensitive preferences

A small persistent key/value store (plain JSON under `AppDirectories.config()`)
for non-sensitive UX state — currently the last opened file (`LAST_FILE`), with
room for window size, recent files, etc. **Reading is forgiving**: a missing or
corrupt file yields empty preferences rather than failing, so a bad prefs file can
never stop the app from starting. Writing is explicit via `save()`.

## `SecretsException`

Unchecked exception for crypto/secret-store failures (bad key, corrupt/tampered
file). Distinct from `UncheckedIOException` used for plain I/O failures.

---

**When you change this, update…** this file (and the relevant Javadoc) whenever
you change the on-disk directory layout, the secrets encryption scheme or threat
model, the preferences format, or the rule about what may/may not be written in
plaintext.
