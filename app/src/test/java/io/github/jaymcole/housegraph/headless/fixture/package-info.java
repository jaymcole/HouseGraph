/**
 * Node types that exist only to be loaded by the headless runner's tests: one that resumes and
 * signals it, and one that throws from its resume the way a node library that has not adopted the
 * viewless lifecycle does.
 *
 * <p>Their own package because {@code NodeRegistry} scans by package, so a test registry can point
 * at these and nothing else. Same precedent as {@code search/fixture/nodes/}.
 */
package io.github.jaymcole.housegraph.headless.fixture;
