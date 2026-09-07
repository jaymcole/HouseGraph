# Modules

A **module** is an ordinary saved graph that another graph uses as a single node.
Build the doorbell logic once, publish it, and every graph that needs a doorbell
gets one node with its ports on it instead of a copy of the wiring.

A module's ports come from four **boundary marker** nodes you place inside it
(Add Node ▸ module):

| Node | Becomes, on the consuming node |
| --- | --- |
| Module Input | a data input |
| Module Output | a data output |
| Module Entry | a flow input — a named way in |
| Module Exit | a flow output — a named way out |

Each marker's **name** is the port's name, so name them before you publish.

## Publish a graph as a module

1. Open or build the graph.
2. Add at least one boundary marker and give it a name.
3. **File ▸ Publish as Module…**
4. Choose a filename. The dialog starts in the modules folder — see
   [Where a module has to live](#where-a-module-has-to-live).
5. HouseGraph writes the graph there, stamps a permanent **module id** into it, and
   tells you what it published.

The graph you published is now the open document, so **File ▸ Save** keeps editing
the module. Publishing again over the same file keeps the same id.

Publishing is a deliberate command, never something Save does for you: a graph that
happens to contain a Module Entry node is not necessarily meant to be a library, and
an id cannot be taken back once other graphs reference it.

Publishing is refused when the graph:

- declares no boundary markers, so it has no interface;
- cannot be read as a graph;
- takes part in a **module reference cycle** — it references a module that, directly
  or through others, references it back.

## Reference a module from another graph

1. Open the consuming graph.
2. Right-click empty canvas and choose **Add Module…**.
3. Pick a module. The list shows each one's interface — `2 in, 1 out · 1 entry,
   1 exit` — and anything that would stop it working.
4. The node lands with the module's ports on it. Wire it like any other node.

The graph you are editing is never offered as a module of itself.

A module runs when control arrives at one of its flow inputs: the entry of that name
fires inside the module, and whichever exits it reaches fire the matching flow
outputs here. With no flow arriving — a plain data pull — the module's outputs are
resolved and no entry fires.

Run `housegraph validate <graph.json>` to check a graph's module references, and
`housegraph run --headless <graph.json>` to run it with no window.

## Where a module has to live

| Who is resolving | Searches |
| --- | --- |
| The desktop app | the **modules** folder (`<app data>/modules`) |
| `housegraph validate`, `housegraph run --headless` | the modules folder, **and** the folder the graph being run sits in |

Resolution is by module **id**, never by path, so a module can be renamed or moved
within a search folder and every graph referencing it keeps working. Publishing a
file outside those folders succeeds and warns you: the id is real, but nothing will
find the file after a restart.

See [server-operations.md](server-operations.md) for where the app data directory is
on each platform.

## Symptoms

| What you see | What happened | What to do |
| --- | --- | --- |
| A module node with a red border and "Not found on this machine" | The module file is not in a search folder on this machine. | Copy the module file into the modules folder, or beside the graph for a headless run. The node keeps its ports, values and edges meanwhile, and re-saves losing nothing. |
| An edge to a module node has disappeared after opening the graph | The module's interface changed and a port was renamed or removed. Edges re-attach by port name. | Rewire it. The Logs window names the module and the node: *"has a different interface than when this graph was saved"*. |
| "No module referenced" on a node added from Add Node ▸ module | The Add-Node menu can only make an unpointed Module node — every module is the same node class. | Delete it and use **Add Module…** instead. |
| Publishing says the graph "declares no module boundary markers" | There is no Module Input/Output/Entry/Exit node on the canvas. | Add one and name it. |
| Publishing says the graph "takes part in a module reference cycle" | A module references itself, directly or through other modules. | Remove the module node that closes the loop. `housegraph validate` names the chain. |
| A new module does not appear in **Add Module…** | The index is read when the picker opens, so this is not staleness — the file is outside the search folders, or has no id. | Publish it, or move it into the modules folder. |
| Two module nodes for one module, and only one of them works | The module registers a **named resource** (see below). | Use it once per app. |
| "Modules are nested more than 16 deep" | A module chain is too deep, or is cyclic in a way publish did not see. | Run `housegraph validate` on the consumer. |

## Limitations

**A module that publishes a resource name can only be used once.** Resource names
are app-wide, not per module, so a second copy of such a module displaces the first
— the log says so when it happens. See
[../nodes/long-lived-resources.md](../nodes/long-lived-resources.md).

**A module's own triggers do not reach its consumer.** A repeating trigger inside a
module fires runs on the module's own graph; those are not the consumer's run, so an
exit they reach fires nothing outside. Put the trigger in the consuming graph.

**Changes to a module reach an open consumer when the consumer is reopened**, not
while it is open.

**There is no second document window.** Editing a module means opening its file as
the current graph, the same as any other graph.

---

**When you change this, update…** this file whenever publishing, referencing or
resolving a module changes. The reference explanations live elsewhere: how a module
executes is [../engine/execution-model.md](../engine/execution-model.md), why a
reference is an id is
[decision 0011](../decisions/0011-modules-are-referenced-by-id.md), and why a module
runs as a nested graph is
[decision 0012](../decisions/0012-a-module-runs-as-a-nested-graph.md).
