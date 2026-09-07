# 0013 — Publishing a module is an explicit command, not a side effect of saving

## Context

A graph becomes referenceable as a module when a stable id is written into its root.
Something has to write that id, and the boundary markers that give a graph an
interface are already visible in every save: `ModuleFile.isModule` can tell, in one
pure pass over a parsed root, whether a graph declares one.

So File ▸ Save could do it. A user who has just built a graph with a Module Entry
node in it plainly means it to be a module, and having to find a second command is
one more thing to know.

## Decision

**Publishing is its own command.** `ModuleLibrary.publish` is the only thing that
mints an id, `File ▸ Publish as Module…` is the only thing that calls it, and
`ModulePublisher` is where the decisions around it live.

Two things settled it.

**A boundary marker is not a declaration of intent.** Someone part-way through
building a module has one on the canvas; so does someone who copied a marker in while
taking a module apart to see how it works. Saving either would stamp a permanent
identity into a file on the strength of a node being present.

**An id cannot be taken back.** Once another graph references it, removing it strands
that consumer — quietly, because a reference is by id and a graph that has lost its id
is not a renamed module but a missing one. A save is the operation a user performs
most often and thinks about least; it is the wrong place for something irreversible.

## Consequences

- One more command to discover. The empty-reference state of a `ModuleNode` names it,
  and so does the picker when nothing is published yet.
- Publishing is a place to say no, which a save could not be: a graph with no
  interface and a graph in a module reference cycle are both refused there rather than
  failing later, when something tries to load them.
- The command is Save-As shaped — it writes the canvas to the file it publishes, and
  that file becomes the open document — so a module and the graph it was published
  from cannot drift apart.
- Nothing stops a graph outside the search folders being published. The id is real;
  the file simply will not be found again, so the publish warns and says where a module
  has to live. See [`../guides/modules.md`](../guides/modules.md).
