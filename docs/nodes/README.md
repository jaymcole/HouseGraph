# Node development

How to write a HouseGraph node, and the conventions a good one follows.

A node is a `BaseNode` subclass. It declares its ports, does its work in
`process()`, and appears in the Add-Node menu automatically. There is no
registration step anywhere.

## Start here

| If you want to… | Read |
| --- | --- |
| Write your first node | [first-node.md](first-node.md) |
| Know what makes a good node | [guidelines.md](guidelines.md) |
| Make your node findable in search | [guidelines.md#tag-your-node-so-it-can-be-found](guidelines.md#tag-your-node-so-it-can-be-found) |
| Declare inputs, outputs and flow ports | [ports-and-values.md](ports-and-values.md) |
| Branch, join, or loop | [flow-control.md](flow-control.md) |
| Give a node its own inline UI | [inline-ui.md](inline-ui.md) |
| Persist settings, or run something on startup | [state-and-startup.md](state-and-startup.md) |
| Own a connection, server or bot | [long-lived-resources.md](long-lived-resources.md) |
| Change ports based on wiring or settings | [dynamic-ports.md](dynamic-ports.md) |
| Control re-entrancy, concurrency or timeouts | [execution-tuning.md](execution-tuning.md) |
| Ship nodes as an installable library | [publishing-a-library.md](publishing-a-library.md) |
| Test what you wrote | [testing-nodes.md](testing-nodes.md) |

Engine internals are in [`../engine/`](../engine/). This section only covers what
a node author needs.

## Where nodes live

**In this repository:** `app/src/main/java/.../graph/nodes/<category>/`. Current
categories are `constants`, `control`, `converters`, `debug`, `loader`, `math`,
`object`, `resource` and `viewers`. This is the built-in library — **dependency-free
primitives only**. Anything needing a third-party library belongs out of tree.

**Out of tree:** its own repository, compiled against `housegraph-api`, installed
at runtime. Start from the
[plugin template](https://github.com/jaymcole/housegraph-plugin-template); the
first-party libraries live in
[housegraph-nodes](https://github.com/jaymcole/housegraph-nodes). See
[publishing-a-library.md](publishing-a-library.md).

The folder name becomes the node's menu category. Add a new category folder only
when a node genuinely does not fit an existing one, and note it here when you do.

A node's **category** is its folder, and therefore its position in the Add-Node menu.
That is separate from its **kind** (`@Node.Kind`), which is the role it plays and cuts
across folders. See [guidelines.md](guidelines.md#tag-your-node-so-it-can-be-found) and
[`../engine/node-search.md`](../engine/node-search.md).

## API stability

`housegraph-api` is **not stable yet**. Breaking changes happen. If you maintain an
out-of-tree library, expect to rebuild it against new versions; if you change the
API, update the companion repositories in the same pass.

---

**When you change this, update…** this file whenever you add a page here, add a
node **category** folder, or change where nodes may live.
