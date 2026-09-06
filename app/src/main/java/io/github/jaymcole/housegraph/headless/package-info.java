/**
 * Running one graph with no window: {@link io.github.jaymcole.housegraph.headless.HeadlessRunner}
 * is the whole program behind {@code housegraph run --headless <graph>}, and
 * {@link io.github.jaymcole.housegraph.headless.HeadlessGraph} is the part of it worth calling on
 * its own — a save file onto a live {@code NodeGraph}, with its running nodes resumed.
 *
 * <p>Its own package, headless like {@code loader}, {@code plugin}, {@code cli} and {@code remote},
 * because of who it is for. Not {@code ui/}: there is no canvas here, and a package below the UI
 * may not reach up into it. Not {@code remote/} either — that package <em>supervises</em> child
 * processes, and this one is a child, so folding them together would make the supervisor depend on
 * the thing it supervises.
 *
 * <p>What sits below it is already built: {@code ui/io/GraphFileIO} parses the file,
 * {@code loader/GraphLoader} turns the snapshot into nodes and edges, and {@code sdk.AutoStartable}
 * resumes the ones that were running when the graph was saved. This package adds only what a
 * process needs around those: logging and a class loader at the front, staying alive in the middle,
 * and a bounded teardown with an {@code remote.ExitCodes} value at the end. See
 * {@code docs/engine/remote-runtime.md}.
 */
package io.github.jaymcole.housegraph.headless;
