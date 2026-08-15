# Troubleshooting

## Where to look first

**In the app:** the **Logs…** toolbar button. It keeps capturing whether or not the
window is open, so opening it after something went wrong still shows you the
history. Each output has its own level dropdown, and rows can be copied.

**On a server:**

```bash
tail -f ~/Library/Application\ Support/HouseGraph/logs/housegraph.log
```

**Either:**

```bash
housegraph doctor
```

## In the editor

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| A node has a red border | A required input has no edge and no value | Hover it — the tooltip names the inputs. Wire or type a value, or clear the input's *Required* checkbox from the right-click menu |
| A port won't accept a connection | The types cannot be bridged | A red anchor while dragging means no conversion exists. Insert a converter node |
| A node exists but does nothing when triggered | It has no flow input | Only nodes with a flow-in port can be triggered along a flow edge. Pure data nodes are pulled, not pushed |
| Two branches reconverge and the node fires twice — or too early | An ordinary node fires on the first arrival | Use a **Join** node to wait for all branches |
| A slow node blocks others | It shouldn't — runs are concurrent | If two `PARALLEL` triggers fan into one shared node, that node serializes them unless it is also set `PARALLEL`. Right-click → Execution Policy |
| A node keeps running after I changed its inputs | Default `QUEUE` finishes the current run first | Set it to `RESTART` if only the newest input matters |
| Nodes show as placeholders | A node library isn't installed | See below |
| A value I typed didn't save | The field is a computed output, or holds a secret | Only manually-authored, non-secret values are saved |

## Node libraries

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Nodes show as placeholders | The library isn't installed | `housegraph check <graph>`, then install it from **Node Libraries…** |
| Placeholders, and no install is offered | The graph was saved before the library recorded its repository | Open it on a machine that has the library and re-save. That repairs the file permanently |
| A library change didn't take effect | Nodes from some library are on the canvas | The status says "Pending restart". Restart the app |
| Install rejected: bundles `housegraph-api` or `slf4j` | The library's build uses `implementation` instead of `compileOnly`, or doesn't exclude `slf4j-api` | A library bug. See [`../nodes/publishing-a-library.md`](../nodes/publishing-a-library.md) |
| A library's log lines never appear | Same cause — a bundled SLF4J binding | Same fix |
| Update check fails | GitHub's 60 requests/hour limit | Wait, or select fewer rows |

## Secrets

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| A node can't find its secret | The key doesn't exist on *this* machine | The store does not sync. Add it through **Secrets…** on the machine that runs the graph |
| "SecretsException" on startup | The store file is corrupt or was tampered with | Encryption is authenticated, so a damaged file fails rather than returning garbage. Restore it, or delete `secrets.enc` and re-enter your keys |

## Server

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Graph loads but nothing ever happens | Its trigger wasn't running when you saved | [Part 2 of the setup guide](server-setup.md#2-author-graphs-that-actually-start). Look for `"running": "true"` in the file |
| `doctor` says git is MISSING | No command line tools | `xcode-select --install` |
| `doctor` says "not a jar" | Running from an IDE or exploded classes | Run the jar built in [Part 3](server-setup.md#3-build-it-on-the-server) |
| `sync` fails with a permission error | Deploy key not set up or not offered | Re-check `git ls-remote` in [Part 4](server-setup.md#4-let-the-server-read-your-graphs-repository) |
| "no `housegraph.json` at its root" | Manifest missing or in a subfolder | It must be at the repository root |
| "Manifest lists X but there is no such file" | Path typo, or the graph wasn't committed | Paths are relative to the repo root |
| "Not installing X … add it to trustedPluginRepositories" | The install gates are closed | Install by hand, or open the gates ([Part 6](server-setup.md#6-node-libraries)) |
| A graph restarts over and over | It fails on startup | The log has the reason. Retries slow to once a minute rather than spinning |
| A graph stopped and won't retry | It exited with a configuration error | Deliberate — a permanent fault isn't retried forever. Fix it and push; a new commit revives it |
| Nothing runs after a reboot | No auto-login, so no GUI session | [Part 9](server-setup.md#9-make-the-mac-behave-like-a-server) |
| Fails at launch complaining about native libraries | Jar built on a different platform | Rebuild on this machine ([Part 3](server-setup.md#3-build-it-on-the-server)) |
| A pushed change never arrives | Wrong branch, or the daemon isn't running | `launchctl list \| grep housegraph`, and check `repositories[].branch` |
| Edits inside `remotes/` disappeared | It's a mirror, reset on every sync | Edit in your repository and push |
| A library was updated but graphs still use the old one | Libraries only change in a fresh process | Graphs restart on a repository change; if you installed by hand, restart the daemon |

## Still stuck

Turn the file log up to `TRACE` from the log window's per-output dropdown — the
choice persists across launches — reproduce, and read `housegraph.log`. Every
refusal the daemon makes is logged with both the reason and the fix.

---

**When you change this, update…** this file whenever a new failure mode gets a
clear diagnosis, or an error message changes.
