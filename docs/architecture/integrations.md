# External Integrations

HouseGraph talks to several external worlds: Discord (chat bots), IP cameras
(ONVIF/Reolink), an Arduino "squirrel alarm" sign, and the local network itself
(hosting a website on a `.local` name). Each integration keeps its client code in
a dedicated package and surfaces to the graph through nodes under
`graph/nodes/<category>/`. None of the engine depends on these — they depend on
the engine.

## Discord (`discord/`, nodes in `graph/nodes/discord/`)

- **`DiscordBot`** — a thin wrapper around a JDA gateway connection (the long-lived
  resource behind a Discord bot node). JDA keeps the connection alive
  (heartbeats/reconnects) while held. `connect(token)` logs in and blocks until
  ready (call it off the UI thread); `disconnect()` shuts down. Incoming non-bot
  messages go to `setMessageHandler`; slash commands are registered via
  `syncCommands` and their invocations forwarded to `setSlashHandler`, deferred so
  a slow graph has ~15 min to answer via a `DiscordReply` handle; `sendMessage`
  posts to a channel by id.
  - **Reading message content requires the privileged `MESSAGE_CONTENT` intent**
    enabled in Discord's developer portal; slash commands need no special intent.
- **`SlashCommandRegistry`** — where slash-command nodes declare their commands by
  bot name, independent of load order (see [resources.md](resources.md)). The bot
  syncs the declared set to Discord on Connect.
- **`CommandMatcher`** — matches an incoming message against a text trigger (e.g.
  `!deploy`) with whitespace/end-of-message boundaries, and extracts the argument
  text.
- Value types: `DiscordMessage`, `DiscordSlashCommand`, `DiscordReply`,
  `CommandOption`, `DiscordOptionType`, `SlashCommandSpec` describe events and
  command declarations passed through the registry / handlers.
- **Secrets:** the bot token comes from `SecretsStore` by key — never wired,
  never saved. See `DiscordBotNode` and [storage-and-secrets.md](storage-and-secrets.md).

## Cameras (`camera/`, nodes in `graph/nodes/camera/`)

A Java port of the AnimalNotifier discovery tooling. Pure JDK, no camera SDK.

- **`CameraDiscovery`** — finds IP cameras on the local network. Primary method is
  **ONVIF WS-Discovery** (a SOAP Probe multicast to `239.255.255.250:3702` over
  UDP on every interface). If nothing answers (Reolink ships with ONVIF off), it
  falls back to a concurrent **TCP port-scan** of each local /24 for the RTSP port
  (554). Each IP is resolved to a **MAC** from the OS ARP cache — the stable key
  for the config. Multicast only reaches the local subnet, so this must run on the
  same network/VLAN as the cameras.
- **`OnvifEnrichment`** — adds *authenticated* ONVIF details (clean model from
  `GetDeviceInformation`, app-set custom name from an authenticated `GetScopes`).
  Auth is a WS-Security `UsernameToken` digest (`Base64(SHA1(nonce+created+
  password))`) so the password never crosses the wire in the clear. Best-effort:
  if ONVIF is disabled/unreachable, calls return empty and the camera keeps what
  it had.
- **`ReolinkClient`** — minimal client for Reolink's HTTP CGI API
  (`/cgi-bin/api.cgi`). Same session hygiene for every call: log in for a
  short-lived token, do the work, log out. Two capabilities:
  - `poll(...)` reads current detection state — batches AI + plain-motion state in
    one request and folds AI categories (`people`/`vehicle`/`dog_cat`) and plain
    `GetMdState` into a single `DetectionState(human, vehicle, animal, motion)`.
  - `snapshot(...)` grabs a single still frame via the `Snap` GET, returning raw
    JPEG bytes (guarded by a JPEG-magic check, since the camera answers errors with
    a JSON body). The package stays JavaFX-free; the node wraps the bytes in an
    `Image`.
- **`CameraConfigStore`** — reads/merges/writes the camera registry
  (`cameras.json` under `config()`), keyed by MAC, each entry `{ name, model,
  lastKnownIp }`. Merging is non-destructive; a malformed file is refused rather
  than clobbered. **This file is not encrypted and deliberately holds no
  credentials** — a camera password is a secret (store it in `SecretsStore`, feed
  it to a camera node's Password input via a Secret Loader).
- **`DiscoveredCamera`** — the value model produced by discovery/enrichment.

## Arduino IoT (`graph/nodes/iot/`, sketch in `extras/squirrel_status/`)

- **`SquirrelAlarmNode`** — the action side of the pattern: when triggered, sends
  an HTTP GET to `http://<host>/<status>`; the device plays the matching animation
  (`bird`, `squirrel`) or blanks the screen (`clear`), auto-reverting after ~30s.
  Control flows straight through (an OUT flow port) so more work can be chained
  after it. Both `Host` and `Status` inputs can be typed or wired.
- **The device** is an Arduino UNO R4 WiFi driving an LED matrix. Its firmware is
  the Arduino sketch under `src/main/java/io/github/jaymcole/housegraph/extras/
  squirrel_status/` (`.ino` + per-animation `.h` files). It advertises itself over
  mDNS as `squirrel-alarm.local`; if that doesn't resolve, use its IP in the
  node's `Host` field. WiFi credentials go in a gitignored `wifi_secrets.h`
  (see `wifi_secrets.h.example`). To add an animation: export it from the Arduino
  LED-matrix editor, save `<name>.h`, and include it in the sketch.

  > Note: `extras/` is not Java — it's device firmware colocated with the node
  > that drives it. It is not compiled by Gradle.

## Local web hosting (`web/`, nodes in `graph/nodes/web/`)

Serves a directory of static files as a website reachable on the LAN at
`http://<name>.local:<port>/`. Like the other integrations, a JavaFX-free client
package (`web/`) holds the machinery and `graph.nodes.web` holds the node.

- **`LocalWebServer`** (`web/`) — the long-lived resource behind the web-server
  node, pairing two pieces:
  - the JDK's built-in `com.sun.net.httpserver.HttpServer` (no dependency) serving
    a base directory, with a directory-index (`index.html`) fallback,
    extension-based `Content-Type`, and **path-traversal rejection** (the resolved
    file must stay inside the base). Requests run on a virtual-thread executor.
  - **jmdns** multicast DNS advertising a `<name>.local` A record plus an
    `_http._tcp` service, so the site resolves from any mDNS-aware device (macOS
    always, Windows 10+, Linux with Avahi). The JDK has no mDNS of its own, hence
    the dependency. jmdns is bound to a non-loopback site-local IPv4 address.

  `start(root, name, port)` binds the socket and joins the multicast group (call it
  off the UI thread); `stop()` tears both halves down and is idempotent. If mDNS
  fails, `start` unwinds the HTTP server so it's all-or-nothing.
- **`WebServerNode`** (`graph/nodes/web/`) — the resource node. Website name,
  directory (chosen with a Browse… button), and port are authored inline and
  persisted via `saveState` — a **directory path, never the files** (the site is
  served live from disk). Liveness is user-driven (Start/Stop, off the UI thread),
  and it registers its `LocalWebServer` in `ResourceRegistry` under the site name;
  torn down in `onRemoved()`. Follows the `DiscordBotNode` resource pattern — see
  [resources.md](resources.md).

**No secrets** — the server hosts public static files and touches nothing in
`SecretsStore`. Note the served directory is exposed on the LAN while running; the
traversal guard keeps requests inside that directory.

## Local ML inference (`ml/`, nodes in `graph/nodes/ml/`)

Vision/ML models run **in-JVM**, locally, through
[Deep Java Library](https://djl.ai) (DJL) on its PyTorch engine — no Python, no
external service. This mirrors how `camera`/`discord` split a headless client
package from its UI nodes: the `ml` package holds JavaFX-free inference clients;
`graph.nodes.ml` holds the nodes that drive them.

- **`ImageNetClassifier`** (`ml/`) — a shared, lazily-loaded ResNet-50 / ImageNet
  classifier. The DJL `ZooModel` is loaded once on first use and reused
  process-wide (a singleton), so multiple classifier nodes don't each pay the
  load cost; a fresh `Predictor` is created per call because `Predictor` isn't
  thread-safe (the model is), which suits the engine's concurrent execution.
  Label-agnostic on purpose — it returns raw ImageNet classes; deciding what they
  *mean* is the caller's job.
- **`AnimalVerdict`** (`ml/`) — the pure, headless-testable policy that collapses
  ImageNet's 1000 labels into `squirrel` / `bird` / `other` / `none`.
- **`AnimalClassifierNode`** (`graph/nodes/ml/`) — converts its JavaFX `Image`
  input to a `BufferedImage` (via `SwingFXUtils`, hence the `javafx.swing`
  module), classifies it, and emits `Category`/`Confidence` plus `Is Squirrel` /
  `Is Bird` gates (1/0) that wire straight into an `If`. It also emits `Objects`
  (`List<String>`) — the model's top-K raw labels with confidences (e.g.
  `["fox squirrel (87%)", "acorn (4%)"]`) for display/logging or downstream
  iteration — rather than rendering them inline.

**Runtime download, not a bundled model.** The first classification after launch
downloads the PyTorch native library and the model weights into DJL's on-disk
cache (under the user's home); later runs are fast and offline. First use
therefore needs network access, like the camera/Discord integrations.

**No secrets, no credentials** — models are public and fetched by DJL; nothing
here touches `SecretsStore`.

**Roadmap.** This is the classifier-first step toward feature parity with the
Python sibling project (AnimalNotifier). Detectors (YOLO/MegaDetector-style),
more classifiers, and a local LLM (via Jlama) are expected to land in `ml/` next;
factor shared model lifecycle/loading into `ml/` rather than duplicating per node.

---

**When you change this, update…** this file whenever you add/modify an
integration (a new Discord capability, a new camera protocol, a new IoT device or
device command, a new local model / inference engine, a change to the web-server
hosting or mDNS behavior) or change how an integration handles credentials.
