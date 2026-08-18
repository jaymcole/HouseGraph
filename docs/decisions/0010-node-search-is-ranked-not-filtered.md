# 0010 — Node search is ranked, and does not index ports

## Context

The Add-Node menu was the only way to find a node: a nested `ContextMenu` built from
`NodeRegistry.discover()`, one submenu per category folder. It works for the two dozen
built-ins and stops working as libraries are installed, because finding something in it
requires already knowing which folder it is in.

Two things were missing. Nodes carried almost no searchable metadata — `@Display.Name`,
and nothing else a person would type. And there was no matching or ranking code in the
repository at all.

## Decision

Declarative metadata in `housegraph-api`; a headless ranked search engine in `app`.

**Ranked rather than filtered.** A substring filter answers only for a user who already
knows the name, which is the case that did not need help. Matching combines character
trigrams with BM25 token rarity, and results below a floor are dropped so a query naming
nothing returns nothing.

**Two matchers, not four.** Trigrams give typo tolerance; rarity neutralises the `Node`
suffix every class carries without a stopword list. Subsequence (fzf-style) matching was
rejected — it ranks `ObjectDecomposerNode` a strong hit for `ode`. Edit distance was
rejected as redundant against trigrams for the cost, and the price is paid openly:
transpositions like `flaot` do not match.

**A four-value `NodeKind`, orthogonal to the category path.** `ACTION`, `CONTROL`,
`RESOURCE`, `DATA` — the vocabulary [`../nodes/guidelines.md`](../nodes/guidelines.md)
already used in prose. Category stays the folder, which is menu structure; kind is the
role, which cuts across folders.

**Ports are not indexed.** This is the decision most likely to be revisited, so: reading
a node's ports means constructing it, because `configureInputs()` builds them lazily.
That would run plugin static initializers at index build — `NodeRegistry` scans with
`Class.forName(name, false, loader)` specifically to avoid that — make one expensive or
throwing constructor everyone's problem, and impose a new "constructors stay cheap" rule
on every node author. Name, keyword, kind, category and library search is good without
it. [`../engine/node-search.md`](../engine/node-search.md) records what adding it later
would touch.

**The engine lives in `app`, the metadata in `housegraph-api`.** Annotations must be
published, because out-of-tree authors tag their own nodes. Ranking constants must not
be, because they will be retuned and every published change means rebuilding
`housegraph-nodes` and the template.

`NodeRegistry.Entry` was deliberately left alone for the same reason. Appending record
components to a published record is binary-incompatible; a separate `NodeMetadata.of()`
reader is additive, so libraries compiled against an older API keep working.

## Consequences

The whole change is `#minor`: new annotations, a new enum, a new type, nothing reshaped.

**Search quality now rests on annotations being written.** A node's findability is its
name, category, description and keywords and nothing else, so all built-ins were tagged
as part of this change. A thin `@Node.Keywords` on a node is a real quality regression,
not a cosmetic one.

**An untagged third-party node is invisible to `kind:` filtering.** The honest
alternative — inferring from the category folder — would be wrong for out-of-tree
libraries, whose category paths are arbitrary. This resolves itself if ports are ever
indexed, since structural inference becomes possible then.

The ranking constants are calibrated against the built-in library rather than derived,
and the separation they rely on (real misspellings score 0.33 and up; coincidental
matches below 0.26) needs re-checking whenever a weight moves.

**Reference:** [`../engine/node-search.md`](../engine/node-search.md)
