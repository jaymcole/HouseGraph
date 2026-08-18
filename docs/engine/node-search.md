# Node search

Ranked search over every node type the registry can offer, in
`app/src/main/java/io/github/jaymcole/housegraph/search/`.

The Add-Node menu answers "show me everything, arranged by folder". Search answers the
other question — *I want to do this; what have I got?* — which a nested menu cannot,
because finding something in it requires already knowing which folder it is in. That
gap widens with every installed library.

Results are **ranked, not filtered**. A query that matches nothing exactly still returns
the closest things, because someone who half-remembers a node's name is the case worth
serving. A query that matches nothing at all returns nothing: a search box that always
shows something teaches users to ignore it.

## What is matched

| Field | Weight | Source |
| --- | --- | --- |
| Display name | 1.00 | `@Display.Name`, else the simple class name |
| Keywords | 0.90 | `@Node.Keywords` |
| Simple class name | 0.80 | the class |
| Save-file type id | 0.80 | `NodeRegistry.persistentTypeId` |
| Description | 0.60 | `@Display.Description` |
| Category path | 0.50 | `NodeRegistry.Entry.categoryPath` |
| Library name | 0.45 | the plugin catalog |
| Library id | 0.40 | `NodeRegistry.Entry.pluginId` |

**Ports are not indexed.** A node's inputs and outputs are built lazily by
`configureInputs()`, so reading them means constructing every discovered node type at
index build — running plugin static initializers early, and making one expensive or
throwing constructor everyone's problem. Everything above is readable by reflection over
an uninitialised `Class`, which is the discipline `NodeRegistry` already keeps.

## How matching works

Two signals, combined.

**Character trigrams** give typo tolerance. Each word is padded (`cat` becomes
`{"  c", " ca", "cat", "at "}`) and the overlap with the query's trigrams is measured as
*containment* — `|Q ∩ F| / |Q|`, how much of what you typed is present. Containment
rather than Dice, because Dice divides by both set sizes and so scores a short query near
zero against a long field however completely it is contained in it. Dice is kept as a
secondary term, where its length sensitivity is wanted: among fields that contain the
query equally well, it prefers the one with least left over.

Padding is what earns prefix preference for free, but it is also the technique's weak
point: for a short query the padding trigrams are a large fraction of the set, so `colr`
overlaps every field beginning "co" without meaning anything. A fuzzy match therefore
also requires at least one shared trigram lying wholly *inside* a word.

**Token rarity (BM25)** weights whole words by how rare they are across the corpus. Its
main job here is defusing the `Node` suffix every class carries: a term appearing in
nearly every document earns an inverse document frequency near zero and stops
influencing the ranking. No stopword list to maintain.

### Tiers

Field matches are tiered, and the gaps are wide enough that no amount of fuzzy
similarity can outrank a literal prefix hit:

| Tier | Score |
| --- | --- |
| Exact | 1.00 |
| Prefix | 0.80 |
| Substring | 0.60 |
| Fuzzy | up to 0.45, scaled by containment |

A user who types the first four letters of a node's name and watches something else jump
to the top has been told the search is broken, and will not trust it again.

Fields combine so the best one dominates and the rest only break ties:

```
textScore = max(w · fieldScore) + 0.25 · Σ remaining (w · fieldScore)
score     = textScore + 0.35 · rarity
```

Summing every field equally would let a node matching four fields weakly beat one
matching its own name exactly. Relevance comes from the strength of the best evidence,
not the quantity of weak evidence.

### Phrase and terms

Each field is scored twice — against the query as one phrase, and as the average over its
individual terms — and the better reading wins. The phrase reading is what makes
`list to string` match the node of that name outright. On its own it dilutes: a query
where only one word is relevant spreads its trigrams across words the field does not
contain, and the overlap falls under the fuzzy threshold even though the word that
mattered matched perfectly.

### Known limit

Trigrams handle omissions and substitutions well and **transpositions poorly**. `flaot`
does not find `Float`, because the transposition destroys every interior trigram. Edit
distance is what catches that case, and it is deliberately not part of this design.

## Query syntax

Bare words rank. `key:value` filters. A leading `-` negates. Quotes hold a phrase
together.

```
kind:control repeating          control-kind nodes, ranked on "repeating"
lib:discord -kind:resource      Discord's nodes except its connection nodes
"image viewer"                  the phrase, not the two words separately
```

| Facet | Aliases | Matches |
| --- | --- | --- |
| `kind:` | `is:` | the node's `NodeKind` |
| `lib:` | `plugin:`, `library:` | library id, or a substring of its name |
| `cat:` | `category:` | category path, by prefix |
| `tag:` | `kw:`, `keyword:` | one of the node's keywords, exactly |

Facets **filter**; free text **ranks**. Different keys AND together; repeating one key
ORs it. A query with facets and no free text is a browse, so it returns everything that
passes, in alphabetical order.

**Nothing is a parse error.** An unrecognised key, or a value that names no `NodeKind`,
folds into the free text and is reported in `SearchQuery.unrecognisedFacets()` for a UI
to hint at. This matters more than it looks: `in:` and `out:` are the facets this design
does not implement, and someone reaching for them should get the nodes they were after
rather than a blank list.

## Kinds

`NodeKind` — `ACTION`, `CONTROL`, `RESOURCE`, `DATA` — is the node's semantic role,
declared with `@Node.Kind`. It is **orthogonal to the category path**, which is the
node's folder and therefore its menu position. A Discord library's nodes share one
category but split across `ACTION` and `RESOURCE`.

A node that declares no kind has none, and matches no `kind:` facet — nor is it swept up
by a negated one, since excluding what was never claimed would be arbitrary. The one
inference made is that implementing `sdk.AutoStartable` implies `RESOURCE`, and only
when nothing was declared: a repeating trigger implements it too, so letting the
interface win would misfile every one of them.

Guessing more than that from structure would need the node's flow ports, which needs an
instance. Guessing from the category folder would be wrong for out-of-tree libraries,
whose category paths are arbitrary. A wrong kind is worse than no kind, because a user
who filters by one never sees the node and has no way to tell why.

## Index lifecycle

`NodeSearchIndex` builds its corpus on first search and caches it. The cache is one
immutable record in a `volatile` field, replaced wholesale under a rebuild lock — the
same shape `NodeRegistry` uses for its own index, so a reader sees either the complete
old corpus or the complete new one, never a half-rebuilt mixture.

`invalidate()` is the counterpart to `NodeRegistry.setRoots`: call it whenever a node
library is installed, updated, removed, enabled or disabled.

## Tuning

Every constant lives in `NodeScorer`. They are calibrated against the real built-in
library, not derived: genuine misspellings of a real node score 0.33 and up, while the
best coincidental match for a query naming nothing sits below 0.26, so the cutoff is
0.25. Re-check that separation after changing a weight.

The scorer lives in `app` rather than `housegraph-api` precisely so that retuning never
changes a published contract or forces an out-of-tree library to be rebuilt.

## If ports are ever indexed

The deferred work, and what it touches:

- `NodeDescriptor` gains port names, types and a `portsKnown` flag — components appended
  to an `app`-internal record, breaking nothing published.
- Building the index calls `NodeRegistry.instantiate` per type, which needs a
  `catch (Throwable)` per node (a plugin's static initializer failing raises an `Error`,
  not an `Exception`) and a documented rule that node constructors stay cheap.
- `QueryParser` gains `in:`, `out:` and `type:`.
- Structural kind inference becomes possible, so untagged third-party nodes stop being
  invisible to `kind:`.
- Connection-compatibility ranking should reuse `TypeConverters.classify`, already the
  single gate shared by drag-time anchor colouring and `NodeGraph.attachEdge`. A second
  notion of compatibility in the search layer would be a bug waiting to happen.

---

**When you change this, update…** this file whenever the indexed fields, the scoring
model, the query syntax or the index lifecycle change, and
[0010-node-search-is-ranked-not-filtered.md](../decisions/0010-node-search-is-ranked-not-filtered.md)
if the reasoning behind them changes.
