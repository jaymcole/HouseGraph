# Logging

HouseGraph has its own logging system in the `logging/` package, providing levels,
multiple simultaneous outputs, and a per-output level on each.

The package is **dependency-free** — it imports nothing from the rest of the app,
and never JavaFX — so any layer may log without creating an import cycle.

## The model

```
   code ──Log.get(X.class)──►  Logger
                                 │  format "{}" + attach throwable
                                 ▼
                            LogManager  ──fan-out, per-sink level check──►  LogSink…
                                                                             ├─ ConsoleSink
                                                                             ├─ FileSink
                                                                             ├─ LogBufferSink
                                                                             └─ DiscordWebhookSink
```

- **`LogLevel`** — `TRACE < DEBUG < INFO < WARN < ERROR`, plus `OFF` as a threshold
  that silences a sink. `OFF` is never a message level. Filtering is ordinal
  comparison (`isAtLeast`).
- **`Logger` / `Log`** — what code touches. Hold one per class:
  `private static final Logger log = Log.get(MyClass.class);`. Messages use
  SLF4J-style `{}` placeholders. A **trailing `Throwable` with no placeholder to
  fill** becomes the record's throwable, so its stack trace reaches the sinks
  rather than being formatted into the text. There is also an explicit
  `error(String, Throwable)` overload.
- **`LogRecord`** — one immutable entry: time, level, source, thread, message,
  optional throwable. The emitting thread name is captured because execution fans
  out across many threads.
- **`LogManager`** — the process-wide hub, fanning each message out to every
  registered sink whose level it clears. **Per-output filtering happens here**, so
  each sink filters independently and a record is materialised only when at least
  one sink wants it. A sink that throws is isolated — reported once to
  `System.err`, never propagated — so logging cannot break the code it observes or
  loop back into itself. Sinks live in a `CopyOnWriteArrayList`, so emitting from
  many threads needs no locking.
- **`LogSink`** (and `AbstractLogSink`) — a destination with its own mutable,
  `volatile` level. Implementations must be thread-safe and quick; a UI-bound sink
  hands off to its toolkit thread rather than working inline.

## The sinks

| Sink | Destination | Default | Notes |
| --- | --- | --- | --- |
| `ConsoleSink` | `System.out`, with `WARN`/`ERROR` to `System.err` | `INFO` | Always present, even before bootstrap and in tests, so nothing is silently lost |
| `FileSink` | `housegraph.log` under `AppDirectories.logs()` | `DEBUG` | Appends, flushes per record. A write failure disables the file once rather than crashing. **Size-rotated**: past 5 MiB it rolls to `housegraph.log.1`, `.2`, … keeping 5 generations |
| `LogBufferSink` | bounded in-memory ring (`Logging.BUFFER_CAPACITY`) | `DEBUG` | Keeps capturing whether or not the window is open, which is what makes the window losslessly re-openable. Notifies live listeners; `snapshot()` replays history |
| `DiscordWebhookSink` | a Discord channel, through an incoming webhook | `WARN` | Off until configured. Not registered at bootstrap — see [External destinations](#external-destinations) |

`ConsoleSink` and `FileSink` share `LogFormat`
(`HH:mm:ss.SSS LEVEL [source] message`, with a stack trace appended), so console
and file read identically.

## Bootstrap

`LogManager` starts with just the console sink. `Logging.bootstrap(Path logDir)` —
called once from `App.start` with `AppDirectories.get().logs()` — adds the shared
buffer and the file sink.

It is idempotent, and takes the log directory as a **parameter** rather than
importing `AppDirectories`, which is what keeps the package cycle-free. Passing
`null` skips file logging, which is what a headless or test run gets.
`Logging.shutdown()`, from `App.stop`, flushes and closes the file.

## External destinations

An **external destination** is an output that sends off this machine, so a graph
running somewhere nobody is watching can still say something. One exists:
`DiscordWebhookSink`, posting to a channel through an incoming webhook. It is
configured from the log window's **External…** dialog and carries its own level, so
a machine can forward `WARN` and above to a channel while the file and the window
stay at `DEBUG`.

It is not registered by `Logging.bootstrap`, because it exists only if someone
configured it. `ui/log/ExternalLogDestinations` reads what was saved, builds the sink
and registers it; `App.start` calls that **before** `LogLevelPreferences.restore`, so
the new sink is one of the outputs whose level gets reapplied, and `App.stop` calls
`shutdown()` so the last queued records are delivered. Enabled-but-unconfigured is
not a state: the destination is registered only when it is switched on *and* a usable
webhook URL is stored.

### The network is kept off the logging hot path

A webhook POST is a network round trip and `publish` is called from every execution
thread, so the sink only enqueues. One daemon worker drains that queue, packs what it
finds into a message and posts it, then waits two seconds before draining again —
which both stays inside Discord's per-webhook rate limit and turns a burst into one
batched message. The first record after a quiet spell still goes out immediately.

The queue is **bounded, and overflows by dropping**. A slow or unreachable webhook
must never become back-pressure on graph execution, so past 1000 queued records new
ones are discarded and counted, and the count is announced in the next message that
gets through — a gap is always visible. `close()` flushes what is queued *unpaced*
and gives up after five seconds; shutdown is not worth holding open for a channel.

### It cannot feed itself

Records emitted from the worker thread are discarded in `publish`, and delivery
failures are reported to `System.err` rather than through `Logger` — the same rule
`FileSink` follows. An unreachable webhook therefore costs one console line on the
way into the outage and one on the way out, not a widening loop.

### What a message looks like

Records are rendered with the same `LogFormat` text as the console and the file and
wrapped in a fenced code block. A message is capped at Discord's 2000 characters: an
over-long batch is split across messages and a single enormous record is truncated
rather than dropped. A record carrying a code fence of its own has it neutered so it
cannot break out of the block, and mentions are disabled on every post, so a log line
containing `@everyone` cannot ping a server.

The request body is assembled and escaped by hand rather than with `org.json`. The
`logging` package depends on nothing but the JDK — the SLF4J bridge below is the one
exception, which is why it sits in a subpackage of its own — and one JSON object with
two fields is not worth breaking that for.

### The URL is a credential

Anyone holding a webhook URL can post to the channel, so it lives in the encrypted
`SecretsStore` under `log.discord.webhook` and never in `preferences.json` — see
[storage.md](storage.md). What is left there is the on/off switch
(`log.discord.enabled`) and the level, under the same `log.level.<sink>` key every
other output uses.

## The SLF4J bridge

Third-party libraries bundled inside node libraries log through SLF4J. Rather than
a stock console binding, HouseGraph ships **its own SLF4J provider** so those logs
flow into the same pipeline as the app's own.

- `HouseGraphSlf4jProvider` implements SLF4J 2.x's `SLF4JServiceProvider` and is
  registered through `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`.
  Dropping the class and that service file on the classpath is all it takes. It is
  the **only** provider on the classpath — the build depends on `slf4j-api`, not
  `slf4j-simple` — so there is no binding ambiguity. This is also why the shared
  plugin class loader must be parent-first; see
  [plugin-runtime.md](plugin-runtime.md).
- `HouseGraphSlf4jLogger`, through `HouseGraphLoggerFactory`, forwards each call
  into `LogManager`, shortening the SLF4J logger's FQCN to a simple name so bridged
  lines read like the app's own `[Source]` labels.
- `Slf4jBridge` holds the bridge's own minimum level and the SLF4J→`LogLevel`
  mapping. It defaults to `WARN`, since libraries are chatty, gating
  below-threshold messages before they reach `LogManager` and reporting that gate
  through SLF4J's `isXxxEnabled()` so a library skips the work. Override at startup
  with `-Dhousegraph.slf4j.level=…` or at runtime with `Slf4jBridge.setLevel`.

This adapter is the one part of the logging system depending on a third-party API,
which is why it lives in its own subpackage.

## The log window

`ui/log/LogWindow` is the on-screen viewer. Its behaviour is described in
[ui-layer.md](ui-layer.md#auxiliary-windows).

Per-output level choices persist across launches: `LogLevelPreferences` saves each
sink's level to `AppPreferences` keyed by sink name, and `App` reapplies them right
after bootstrap. That glue lives in the UI layer, not in `logging/`, so the core
stays free of a `storage` dependency.

---

**When you change this, update…** this file and the relevant Javadoc whenever you
change the level model, add or alter a sink or its default, change the bootstrap
seam, the buffer's lossless-reopen contract, or the SLF4J bridge. Adding an external
destination also touches [ui-layer.md](ui-layer.md), because it appears in the log
window, and [storage.md](storage.md) if it stores a credential. A new on-disk log
location touches [storage.md](storage.md) too.
