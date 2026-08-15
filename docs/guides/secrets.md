# Secrets

Tokens, API keys and passwords go in HouseGraph's encrypted secrets store, never
into a graph file.

## Storing one

Open **Secrets…** from the toolbar. Add a key and its value, and save.

Keys are names you choose — `DISCORD_TOKEN`, `FRONT_CAMERA_PASSWORD`. A node stores
the *key*, and looks up the value when it needs it. The value never enters your
save file.

A gitignored `.env` file at the repository root seeds the dropdown in the Secret
Loader node, which is convenient for keys you already keep there. See
`.env.example`.

## Using one in a graph

Add a **Secret Loader** node, pick a key from its dropdown, and wire its output
into whatever needs the credential — a camera node's Password input, a bot node's
token.

The value only exists while the graph runs. It is marked so it is never written to
disk, and copy/paste never carries it into a duplicated node.

## Where it lives, and what it protects

The store is a single file encrypted with AES-256-GCM under your data directory:

- **Windows:** `%APPDATA%\HouseGraph\secrets\`
- **macOS:** `~/Library/Application Support/HouseGraph/secrets/`
- **Linux:** `~/.local/share/HouseGraph/secrets/`

**Be clear about what this does and does not do.** It keeps your credentials out of
plaintext on disk and out of graph files you might share or commit, and it defends
against casual inspection or an accidentally-synced folder.

**It does not defend against someone who can already read that folder**, because
the encryption key is stored beside the encrypted file. Anyone with access to your
user account can read your secrets.

Node libraries are arbitrary code running with your privileges and can read the
store directly. Install libraries you trust.

## On a server

A server needs its own secrets — the store does not sync with your desktop. Set
them up on that machine, through the editor if it has a display, before deploying
graphs that need them.

A graph that starts and immediately fails on a missing secret is a common cause of
a restart loop; the log will name it. See
[troubleshooting.md](troubleshooting.md).

For a private graphs repository over HTTPS, store the personal access token here
and name its key in `remote.json` — the token is passed to git through its
environment, never on the command line. An SSH deploy key avoids the question
entirely and is the recommended route. See [server-setup.md](server-setup.md).

---

**When you change this, update…** this file whenever the encryption scheme, the
storage location, or the way nodes consume secrets changes. Keep the honest
statement of what it does not protect.
