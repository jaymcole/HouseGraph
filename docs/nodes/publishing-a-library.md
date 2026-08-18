# Publishing a node library

A node library is a jar of `BaseNode` subclasses compiled against
`housegraph-api`, published as a GitHub release, and installed at runtime by
HouseGraph. The app never has to be rebuilt.

> **The build rules live in
> [`../shared/node-library-rules.md`](../shared/node-library-rules.md).**
> That page is the single source of truth, mirrored into `housegraph-nodes` and the
> plugin template so all three repositories teach the same thing. Read it before
> writing a build file — every rule on it has a silent failure mode.
>
> This page covers only what is specific to *this* repository: the published
> coordinate, and where libraries live.

## Where to start

| Writing | Start from |
| --- | --- |
| One library, one repository, independently versioned | [housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template) |
| A first-party library | a subproject of [housegraph-nodes](https://github.com/jaymcole/housegraph-nodes) |

`housegraph-nodes` shares its build rules through a `housegraph-node-library`
convention plugin in `buildSrc`, so a subproject declares only its identity in about
ten lines. The convention plugin already implements every rule on the shared page —
do not override it.

The trade a monorepo makes is lockstep versioning: one tag releases every library at
the same version. In exchange, an API change is one commit and one tag rather than
one pull request and one tag per repository. That is also why a release carries
several jars, and why the `<pluginId>-<version>-all.jar` naming convention is
load-bearing.

## Depending on the API

Published through **JitPack**, which builds a git tag:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    // compileOnly is required, not stylistic -- see the shared rules.
    compileOnly 'com.github.jaymcole:HouseGraph:v1.1.1'
}
```

**On that coordinate.** JitPack documents multi-module projects as
`com.github.<user>.<repo>:<module>:<tag>`, and `housegraph-api/build.gradle`
publishes under exactly that name. JitPack found only one artifact in the build and
relocated it to the repository-level coordinate, so
`com.github.jaymcole.HouseGraph:housegraph-api:v1.1.1` returns 404 while
`com.github.jaymcole:HouseGraph:v1.1.1` resolves.

The artifact named `HouseGraph` *is* `housegraph-api`; `:app` has no publication. If
a second module is ever published from this repository, JitPack switches to
per-module coordinates and this one stops working.

`jitpack.yml` pins JDK 21 and scopes the build to
`:housegraph-api:publishToMavenLocal`. The root build honours `-Pversion=<tag>`, so
the published version always matches the tag.

## The host side

How HouseGraph discovers, loads, installs and updates a library is in
[`../engine/plugin-runtime.md`](../engine/plugin-runtime.md). The trust model is in
[`../engine/security-model.md`](../engine/security-model.md).

---

**When you change this, update…** this file whenever the published coordinate or
the JitPack setup changes. **Build rules and API surface belong in
[`../shared/node-library-rules.md`](../shared/node-library-rules.md)**, so they stay
in sync with the companion repositories — do not restate them here.
