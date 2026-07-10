# Logging

HouseGraph has its own small logging system in the `logging/` package. It exists to
give three things the previous scattered `System.err.println`s couldn't: **levels**
(so noise can be filtered), **multiple outputs** at once, and a **per-output level**
on each — plus an in-app **log window** that can be closed and reopened without
losing anything.

The package is deliberately **dependency-free** (it imports nothing from the rest of
the app, and never JavaFX), so any layer may log without creating an import cycle.

## The model

```
   code ──Log.get(X.class)──►  Logger
                                 │  format "{}" + attach throwable
                                 ▼
                            LogManager  ──fan-out, per-sink level check──►  LogSink…
                                                                             ├─ ConsoleSink   (stdout/stderr)
                                                                             ├─ FileSink      (housegraph.log)
                                                                             └─ LogBufferSink (in-memory ring → LogWindow)
```

- **`LogLevel`** — `TRACE < DEBUG < INFO < WARN < ERROR`, plus `OFF` (a threshold that
  silences a sink; never a message level). Filtering is pure ordinal comparison
  (`isAtLeast`).
- **`Logger` / `Log`** — what code touches. Hold one per class:
  `private static final Logger log = Log.get(MyClass.class);`. Messages use SLF4J-style
  `{}` placeholders; a **trailing `Throwable` with no placeholder to fill** becomes the
  record's throwable (its stack trace reaches the sinks) rather than being formatted into
  the text. There's also an explicit `error(String, Throwable)` overload.
- **`LogRecord`** — one immutable entry (time, level, source, thread, message, optional
  throwable). The emitting thread name is captured because execution fans out across many
  threads.
- **`LogManager`** — the process-wide hub. It fans each message out to every registered
  sink whose level the message clears. **This is where per-output filtering happens**, so
  each sink filters independently and a record is only materialised when at least one sink
  wants it. A sink that throws is isolated (reported once to `System.err`, never
  propagated) so logging can't break the code it observes or loop back into itself.
  Sinks live in a `CopyOnWriteArrayList`, so emitting from many threads needs no locking.
- **`LogSink`** (+ `AbstractLogSink`) — a destination with its own mutable, `volatile`
  level. Implementations must be thread-safe and quick; a UI-bound sink hands off to its
  toolkit thread rather than working inline.

## The three sinks

| Sink | Destination | Default level | Notes |
| --- | --- | --- | --- |
| `ConsoleSink` | `System.out` (`WARN`/`ERROR` → `System.err`) | `INFO` | Always present, even before bootstrap and in tests, so nothing is silently lost. |
| `FileSink` | `housegraph.log` under `AppDirectories.logs()` | `DEBUG` | Appends, flushes per record; a write failure disables the file once rather than crashing. **Size-rotated**: past `DEFAULT_MAX_BYTES` (5 MiB) it rolls to `housegraph.log.1`, `.2`, … keeping `DEFAULT_MAX_BACKUPS` (5) generations, so the logs never grow without bound. |
| `LogBufferSink` | bounded in-memory ring (`Logging.BUFFER_CAPACITY`) | `DEBUG` | Keeps capturing whether or not the window is open — this is what makes the window losslessly re-openable. Notifies live listeners; `snapshot()` replays history. |

`ConsoleSink` and `FileSink` share `LogFormat` (`HH:mm:ss.SSS LEVEL [source] message`,
with a stack trace appended) so console and file read identically.

## Bootstrap

`LogManager` starts with just the console sink. `Logging.bootstrap(Path logDir)` — called
once from `App.start` with `AppDirectories.get().logs()` — adds the shared buffer and the
file sink. It's idempotent and takes the log directory as a **parameter** (rather than
importing `AppDirectories`) precisely to keep the package cycle-free. `Logging.shutdown()`
(from `App.stop`) flushes and closes the file. Passing `null` skips file logging, which is
what a headless/test run gets.

## The log window

`ui/log/LogWindow` is the on-screen viewer — a **standalone top-level stage**, not owned by
the graph window, so it lives and dies independently. `LogWindow.show()` is a
toggle-to-front singleton.

- On open it fills from `LogBufferSink.snapshot()` (the full retained history, including
  everything captured while it was closed) and then attaches a listener for live records;
  on close it detaches the listener. The buffer never stops capturing, so reopening is
  lossless. (There is a vanishingly small snapshot-then-listen gap where a record emitted
  at that exact instant is skipped in the window; it is still on the console and in the
  file.)
- It shows three independent controls that mirror the model: a **display filter** (hides
  rows below a level without discarding them), a **per-output level** dropdown for *every*
  registered sink (make the file quiet while the window stays verbose), and
  **auto-scroll** / **clear**.
- All table mutation is on the FX thread; the buffer listener marshals each record with
  `Platform.runLater`. The window's row list is bounded to the buffer capacity.

## The SLF4J bridge (`logging/slf4j/`)

Third-party libraries — notably JDA — log through SLF4J. Instead of a stock console binding
(`slf4j-simple`), HouseGraph ships its **own SLF4J provider** so those logs flow into the
same `LogManager` pipeline (console, file, window) as the app's own.

- `HouseGraphSlf4jProvider` implements SLF4J 2.x's `SLF4JServiceProvider` and is registered
  via `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`. Dropping the class + that
  service file on the classpath is all it takes — SLF4J discovers it. It is the **only**
  SLF4J provider on the classpath (`build.gradle` depends on `slf4j-api`, not
  `slf4j-simple`), so there's no binding ambiguity.
- `HouseGraphSlf4jLogger` (via `HouseGraphLoggerFactory`) forwards each call into
  `LogManager`, shortening the SLF4J logger's FQCN to a simple name so bridged lines read
  like the app's own `[Source]` labels.
- `Slf4jBridge` holds the **bridge's own minimum level** (the SLF4J→`LogLevel` mapping too).
  It defaults to `WARN` — libraries are chatty, and this matches the old `slf4j-simple`
  "warn" setting — gating below-threshold messages before they reach `LogManager` (and
  reporting that gate through SLF4J's `isXxxEnabled()` so a library skips the work). Override
  at startup with `-Dhousegraph.slf4j.level=…` or at runtime via `Slf4jBridge.setLevel`.

This adapter is the one part of the logging system that depends on a third-party API (the
SLF4J API); it lives in its own subpackage to keep the core dependency-free.

---

**When you change this, update…** this file (and the relevant Javadoc) whenever you change
the level model, add/alter a sink or its default level, change the bootstrap seam, the
buffer's lossless-reopen contract, the log window's controls, or the SLF4J bridge (provider
registration, the bridge level, or the level mapping). New on-disk log location → also
update [`storage-and-secrets.md`](storage-and-secrets.md).
