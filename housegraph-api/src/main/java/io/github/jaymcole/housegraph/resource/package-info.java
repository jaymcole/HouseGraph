/**
 * The name-keyed coordination hub for long-lived resources.
 * <p>
 * {@link io.github.jaymcole.housegraph.resource.ResourceRegistry} offers object lookup
 * ({@code register}/{@code find}) and event pub/sub ({@code publish}/{@code subscribe}),
 * both keyed by a user-chosen name so nodes reference a resource without being wired to
 * it. {@link io.github.jaymcole.housegraph.resource.Subscription} cancels a subscription.
 * <p>
 * See {@code docs/architecture/resources.md}.
 */
package io.github.jaymcole.housegraph.resource;
