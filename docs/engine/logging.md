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
                                                                             └─ LogBufferSink
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
seam, the buffer's lossless-reopen contract, or the SLF4J bridge. A new on-disk log
location also touches [storage.md](storage.md).
