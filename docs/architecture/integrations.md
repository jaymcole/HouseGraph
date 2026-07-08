# External Integrations

HouseGraph talks to three external worlds: Discord (chat bots), IP cameras
(ONVIF/Reolink), and an Arduino "squirrel alarm" sign. Each integration keeps its
client code in a dedicated package and surfaces to the graph through nodes under
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

---

**When you change this, update…** this file whenever you add/modify an
integration (a new Discord capability, a new camera protocol, a new IoT device or
device command) or change how an integration handles credentials.
