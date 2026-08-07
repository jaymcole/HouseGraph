# External Integrations

This repository still hosts one integration with the local network itself
(hosting a website on a `.local` name) plus local ML inference; each keeps its
client code in a dedicated package and surfaces to the graph through nodes under
`graph/nodes/<category>/`. None of the engine depends on these — they depend on
the engine. Everything else has moved to out-of-tree node libraries — see the
notes below and [plugins.md](plugins.md).

## Discord — **extracted**

> This integration no longer lives in this repository. It is the
> `housegraph-discord` library in
> [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes), installed
> through **Node Libraries…**. It was the hardest extraction — a sibling client
> package, `SecretsStore` access (now `sdk.Secrets`), `ResourceRegistry`,
> `Subscription`, both `AutoStartable` and `NodeContentProvider`, dynamic ports via
> `rebuildPorts`, a `saveState` map — deliberately done second, once `iot` had
> proven the pipeline, so any gap the hardest case exposed was found while only one
> plugin existed to fix up. See [plugins.md](plugins.md).
>
> One build wrinkle worth knowing if you extract something else that bundles a
> library depending on SLF4J: **JDA depends on `slf4j-api` itself**, and left
> alone that transitive dependency ends up in the shaded jar too — which the
> host's install-time validation rejects a jar for, because a bundled `slf4j-api`
> means a second, silently-swallowing logging binding. The library's build
> excludes it explicitly (`exclude group: 'org.slf4j', module: 'slf4j-api'`
> on the JDA dependency); `housegraph-api` already supplies the real one.
>
> Also: a node combining `@Node.Type` (which needs
> `io.github.jaymcole.housegraph.annotations.Node`) with `NodeContentProvider`
> (which returns `javafx.scene.Node`) hits an import collision — both are named
> `Node`. None of this repository's own nodes had ever combined the two. Fix:
> don't import `javafx.scene.Node`; write `javafx.scene.Node` fully qualified at
> each use. See the discord nodes for the pattern, or `HelloWorldNode` in the
> template, which already used this to avoid the same collision.

## Cameras — **extracted**

> This integration no longer lives in this repository. It is the
> `housegraph-camera` library in
> [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes), installed
> through **Node Libraries…**. Third extraction, and — unlike Discord — an easy
> one: no third-party dependency at all (ONVIF/Reolink are plain HTTP/SOAP over
> `java.net.http.HttpClient`, discovery is plain JDK sockets), so nothing here
> needed the SLF4J-exclude or asset-naming lessons Discord surfaced. The
> `@Node.Type`-vs-`NodeContentProvider` `Node` import collision still applied,
> since three of these nodes have an inline UI. See [plugins.md](plugins.md).

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
  (`cameras.json` under the library's own config location), keyed by MAC, each
  entry `{ name, model, lastKnownIp }`. Merging is non-destructive; a malformed
  file is refused rather than clobbered. **This file is not encrypted and
  deliberately holds no credentials** — a camera password is a secret, resolved
  via `sdk.Secrets` and fed to a camera node's Password input via a Secret Loader.
- **`DiscoveredCamera`** — the value model produced by discovery/enrichment.

## Arduino IoT — **extracted**

> This integration no longer lives in this repository. It is the `housegraph-iot` library in
> [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes), installed through
> **Node Libraries…**, and the Arduino sketch moved with it — firmware and the node that
> drives it are no use apart. It was the first extraction precisely because it depends on
> nothing but the JDK and the node model, so it exercised the whole pipeline with nothing
> else that could fail. See [plugins.md](plugins.md).
>
> The description below is kept because the pattern it demonstrates — an action node whose
> control flows straight through, with inputs that can be typed or wired — is still the
> model for writing one.

- **`SquirrelAlarmNode`** — the action side of the pattern: when triggered, sends
  an HTTP GET to `http://<host>/<status>`; the device plays the matching animation
  (`bird`, `squirrel`) or blanks the screen (`clear`), auto-reverting after ~30s.
  Control flows straight through (an OUT flow port) so more work can be chained
  after it. Both `Host` and `Status` inputs can be typed or wired.
- **The device** is an Arduino UNO R4 WiFi driving an LED matrix. Its firmware is
  the Arduino sketch under `housegraph-iot/firmware/squirrel_status/` in the
  housegraph-nodes repository (`.ino` + per-animation `.h` files). It advertises itself over
  mDNS as `squirrel-alarm.local`; if that doesn't resolve, use its IP in the
  node's `Host` field. WiFi credentials go in a gitignored `wifi_secrets.h`
  (see `wifi_secrets.h.example`). To add an animation: export it from the Arduino
  LED-matrix editor, save `<name>.h`, and include it in the sketch.

  > Note: `extras/` here now holds only the sample website for the web-server node. It is
  > not Java, so it lives at the repository root rather than inside a source set.

## Local web hosting (`web/`, nodes in `graph/nodes/web/`)

Hosts a website reachable on the LAN at `http://<name>.local:<port>/`, either by
serving a directory of static files from the JVM (`WebServerNode`) or by launching an
external Node.js server as a child process (`NodeServerNode`). Like the other
integrations, a JavaFX-free client package (`web/`) holds the machinery and
`graph.nodes.web` holds the nodes.

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
- **`WebServerNode`** (`graph/nodes/web/`) — the node. Website name, directory
  (chosen with a Browse… button), and port are authored inline and persisted via
  `saveState` — a **directory path, never the files** (the site is served live from
  disk). Liveness is user-driven (Start/Stop, off the UI thread), and it registers
  its `LocalWebServer` in `ResourceRegistry` under the site name; torn down in
  `onRemoved()`. It also has a **`Store` data input** — see below.

Both server classes share **`LanAddress`** (`web/`) for picking the non-loopback
site-local IPv4 to advertise over mDNS, so the choice is made one way.

### Hosting a Node.js server instead (`NodeProcessServer` / `NodeServerNode`)

For apps that aren't just static files — an Express server, a Vite dev server,
anything `npm start` runs — the **Node-server node** hosts by launching an external
Node.js process rather than serving from the JVM.

- **`NodeProcessServer`** (`web/`) — the JavaFX-free resource. `start(dir, command,
  name, port)` spawns the command through the platform shell (`cmd /c` on Windows,
  `sh -c` elsewhere, so PATH-resolved launchers like `npm`/`npx` work as typed) in
  the chosen project directory, pumps the child's merged stdout/stderr into the log
  (`[node] …`), and advertises `<name>.local:<port>` over the same jmdns mDNS as
  `LocalWebServer`. `stop()` kills the whole descendant process tree (so `npm start`'s
  forked node doesn't orphan and keep the port) and tears down mDNS; it's idempotent.
  If mDNS fails, `start` kills the process so it's all-or-nothing.
- **It does not bind the port** — the Node process does. HouseGraph exports the
  declared port as `PORT` into the child's environment and advertises it, but the
  Node app must actually listen on it (e.g. `app.listen(process.env.PORT)`); if they
  disagree, the advertisement points nowhere.
- **`NodeServerNode`** (`graph/nodes/web/`) — the node. Server name, project directory
  (Browse…), **start command** (default `npm start`), and port are authored inline and
  persisted via `saveState` — a directory path and command string, **never the project
  files**. Same user-driven Start/Stop-off-the-UI-thread lifecycle as `WebServerNode`,
  registered in `ResourceRegistry` under its name and torn down in `onRemoved()`. It has
  **no `Store` input** — a Node app owns its own routes and persistence.

**Trust boundary:** the start command is user-authored config run as a local child
process — the same trust as any local command the user types. Nothing here touches
`SecretsStore`.

### Server-side storage for the hosted site (`/api/data`)

A static site can't persist shared state on its own. Wire a **data-store node**
(`graph/nodes/loader/DataStoreNode`, backed by `store/JsonDocumentStore`) into the
web-server node's **`Store` input**, and `LocalWebServer` mounts a small JSON API
beside the files so the page can read/write server-side, shared-across-devices data:

- `GET /api/data` → the current JSON document; `PUT /api/data` → replace it
  (body bounded to 1 MiB → `413` past that, `400` for non-JSON).
- `HttpServer` longest-prefix routing sends `/api/data` to the API handler and
  everything else to the static-file handler. Because the API is the **same
  origin** as the page, browser `fetch()` needs no CORS setup.
- **The store arrives on a data edge, not by name** — a deliberate choice so the
  dependency is visible on the canvas (see the note in [resources.md](resources.md)).
  Data is pulled, and the server isn't flow-driven, so the node pulls the `Store`
  input **once at Start**: `beginProcessing()` resolves the edge and `process()`
  captures the `JsonDocumentStore` handle (the resolved value only lives in the run's
  overlay, readable inside `process()`). Re-wiring therefore takes effect on the next
  Start, like the other settings. `LocalWebServer` itself depends only on a narrow
  `DocumentApi` seam; with nothing wired the site is static-only and `/api/data`
  answers `503`.
- **Sharing is by wiring _or_ by name.** Fan one data-store node's output out to several
  web-server nodes and they share the store. Two data-store nodes with the **same name**
  also share it: a store is keyed by its name to a file, and `DocumentStores` vends one
  `JsonDocumentStore` instance per file, so same-name nodes get the *same* consistent
  handle (not two objects racing on one file). `JsonDocumentStore` is thread-safe, so
  concurrent writes are safe (last-write-wins on the whole document).
- **The name is the store's recoverable identity.** The document lives at
  `AppDirectories.dataStore(<name>)/document.json`. Because the key is the user's name and
  not an opaque id, the data survives the node: delete and recreate with the same name and
  the document is still there. Renaming just points the node at a different store; the old
  one stays on disk under its name (reachable via the node's **Open folder** button) and
  returns if you type its name again. The default name is `store`, so even a never-renamed
  node recovers its data on recreate. See [storage-and-secrets.md](storage-and-secrets.md).

**No secrets** — the server hosts public static files and touches nothing in
`SecretsStore`. Note the served directory (and the data store, if wired) is exposed
on the LAN while running; the traversal guard keeps file requests inside the served
directory.

## Local ML inference (`ml/`, nodes in `graph/nodes/ml/`)

Vision/ML models run **in-JVM**, locally, through
[Deep Java Library](https://djl.ai) (DJL) on its PyTorch engine — no Python, no
external service. This mirrors the split every extracted integration also used —
`camera`, `discord` — a headless client package from its UI nodes: the `ml`
package holds JavaFX-free inference clients; `graph.nodes.ml` holds the nodes
that drive them. `ml` is the next candidate for extraction (see
[plugins.md](plugins.md)).

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
therefore needs network access.

**No secrets, no credentials** — models are public and fetched by DJL; nothing
here touches `SecretsStore`.

**Roadmap.** This is the classifier-first step toward feature parity with the
Python sibling project (AnimalNotifier). Detectors (YOLO/MegaDetector-style),
more classifiers, and a local LLM (via Jlama) are expected to land in `ml/` next;
factor shared model lifecycle/loading into `ml/` rather than duplicating per node.

---

**When you change this, update…** this file whenever you add/modify an
integration that still lives in this repository (a new local model / inference
engine, a change to the web-server hosting or mDNS behavior) or change how an
integration handles credentials. Extracted integrations (Discord, IoT, Camera)
are documented in the housegraph-nodes repository; update this file's
"extracted" note only if the extraction story itself changes.
