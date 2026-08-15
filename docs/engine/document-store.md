# Document store

`store/` is a small persistent JSON store in the published API, backing the
data-store node. Its purpose is shared, server-side state that outlives a run and
is reachable from outside the graph — most obviously by a website hosted by the
`housegraph-web` library, which serves it over an `/api/data` endpoint.

It is JavaFX-free, like everything else in `housegraph-api`.

## `JsonDocumentStore`

One JSON document, persisted to one file, written atomically.

| Member | Does |
| --- | --- |
| `get()` | the current document as a string; `EMPTY_DOCUMENT` (`{}`) when there is none |
| `set(String json)` | replace the document |
| `length()` | its size |
| `addChangeListener` / `removeChangeListener` | observe writes |

**Thread-safe.** Reads and writes synchronize on an internal lock, so concurrent
writes are safe, with last-write-wins on the whole document. That matters because
the engine runs nodes concurrently and an HTTP handler may write from a request
thread while a node reads.

The whole document is the unit of replacement. There is no field-level merge.

## `DocumentStores`

`DocumentStores.forFile(path)` vends **one `JsonDocumentStore` instance per file**.

This is what makes sharing work. Two data-store nodes with the **same name** get
the *same* handle rather than two objects racing on one file, and a single store's
output can also fan out over data edges to several consumers.

## Identity is the user's name, not an id

The document lives at `AppDirectories.dataStore(<name>)/document.json`.

Keying on a user-chosen name is deliberate: **the data survives the node.** Delete
the node and recreate one with the same name, and the document is still there.
Renaming points the node at a different store; the old one stays on disk under its
name and returns if the name is typed again. The default name is `store`, so even a
never-renamed node recovers its data on recreate.

An opaque id would strand data under a key nobody can retype.

## Why it is a data edge, not a registry name

`DataStoreNode` hands its `JsonDocumentStore` to a consumer over a **data edge**
rather than registering by name, so the dependency is visible on the canvas. This
is the worked example of the rule in
[`../nodes/long-lived-resources.md`](../nodes/long-lived-resources.md): reach for
`ResourceRegistry` when a resource is *broadcast*, and for a data edge when it is
point-to-point.

Because data is pulled and a server is not flow-driven, a consumer pulls the store
input **once at start**: `beginProcessing()` resolves the edge and `process()`
captures the handle, since the resolved value only lives in the run's overlay.
Re-wiring therefore takes effect at the next start.

## This is runtime user data, not graph config

The document is written **outside the graph save**, following the same discipline
as computed values: the `.json` save holds authored configuration, not the store's
contents. See [storage.md](storage.md).

Nothing here touches `SecretsStore`. A hosted site exposes whatever is in the
document on the LAN while the server runs, so do not put a credential in it.

---

**When you change this, update…** this file and the `store/` Javadoc whenever you
change the store API, its thread-safety guarantees, the per-file instance rule, or
where documents live on disk.
