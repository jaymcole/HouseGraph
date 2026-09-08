# Find in the open graph

`Ctrl/Cmd+F` on the canvas opens a find bar in its top-right corner and rings every
matching node in yellow. `search/GraphSearch` decides what matches; `ui/GraphCanvas`
owns the bar and the highlighting.

This is not [node search](node-search.md), and the two answer opposite questions.
That index answers *which node type did I mean?* — one winner, half-remembered, so it
ranks and tolerates typos. This answers *where in this graph is the thing I am looking
at?* — a set, on a canvas the user is already looking at.

## Why it filters

A find highlights a set, so there is no winner for a ranking to pick and nothing for it
to order. And every extra yellow border is a place the user has to go and look, so a
fuzzy hit costs more here than a missed one: it sends someone across the canvas to a
node they did not ask about. So the test is literal containment, and a query matching
nothing highlights nothing.

## What is matched

| Field | Source |
| --- | --- |
| Display name | `BaseNode.getName()` |
| Simple class name | the node's class |
| Data port names | `getInputs()`, `getOutputs()` |
| Flow port names | `getFlowInputs()`, `getFlowOutputs()` |
| Authored values | each port's value, formatted through `ValueEditors` |

**Ports are matched here, unlike in the type index.** These nodes are already built, so
their `configureInputs()` has already run and reading their ports costs nothing — the
whole reason [node-search.md](node-search.md#what-is-matched) cannot do it does not
apply once a node is on the canvas.

**Values are the point.** A graph with thirty `Add` nodes tells them apart by nothing in
their names, and the address or room name someone is hunting for lives in a field.

## What is never matched

A value is read only when `NodeVariable.isPersistentValue()` — manually authored,
non-secret, non-transient — and its type has a registered editor, which together are
exactly the conditions under which `PortView` shows an inline field. So a find matches
what is on screen and nothing else:

- **No secret is searchable.** The gate is the one persistence uses, so a secret is no
  more visible to a find than it is to a save file — see [storage.md](storage.md). Not a
  claim that the store defends against someone holding the disk, which it does not
  ([security-model.md](security-model.md)); only that nothing the app draws hands back a
  value it was told to hide.
- **No computed value is searchable.** Otherwise what a find highlighted would depend on
  whether the graph had run yet, which is not something a user can see or reason about.

## Matching

One containment test per node, both sides normalised by `SearchText` — the same
normalisation the type index uses, so `192.168.0.14` and `168 0 14` are the same query
and neither depends on how the punctuation was typed.

Each field is written into the node's haystack twice, normalised whole and again split
on camelCase, so `addno` and `add node` both find `AddNode` without a second pass or a
second notion of a word boundary. Fields are separated by a newline, which normalisation
can never produce, so a match always lies wholly inside one field: a node named "Add"
with a port called "Node" is not a hit for `add node`.

Nothing is cached. A node's searchable text changes under it constantly — a typed value,
a rebuilt port list, a bound module — and a cache keyed on none of those would go stale
in exactly the case a find is for. It is a handful of short strings per node, rebuilt on
each keystroke.

## The bar

The find bar is a child of the `GraphCanvas` `Pane` rather than of its content `Group`,
so it floats at a fixed corner instead of panning and zooming with the graph.
`layoutChildren` is the only place its position is set, because `Region`'s layout pass
sizes managed children without moving them, and the position depends on the bar's own
width — which changes as the match count text does.

It highlights and counts; it never navigates. Nothing pans, selects or reorders, so a
find cannot disturb a layout or a selection mid-edit. The count is what makes an
off-screen hit discoverable, and is the reason the bar reports "no matches" rather than
simply showing nothing.

Highlighting is a `NodeView` overlay rectangle like the selection and pulse borders (see
[ui-layer.md](ui-layer.md#node-visual-states)), painted **over** the selection border and
wider than it, so a node that is both selected and a hit reads as a hit — that is the
state the user is scanning for. Image export clears the highlights exactly as it clears
the selection: an open find bar is not part of the graph, so it is not part of the
picture.

---

**When you change this, update…** this file whenever the matched fields, the
value-visibility gate, the matching rule or the find bar's behaviour change.
[node-search.md](node-search.md) owns the separate ranked search over node *types*;
[ui-layer.md](ui-layer.md) owns the canvas that hosts the bar.
