package io.github.jaymcole.housegraph.catalog;

import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;

/**
 * A short fingerprint of a node type's observable shape: its kind, its inputs and outputs (name,
 * type, required-ness), its flow ports, and the keys of its default {@code saveState()} — the
 * facts a save file binds against by name and a harness needs to know did not move.
 *
 * <h2>Why a hash rather than the shape itself</h2>
 * {@link NodeCatalog} already exports the full shape for a human or a harness to read. This exists
 * for the cheap, offline comparison: a save file can carry one short string per node, and a later
 * check only needs to recompute the same string and compare — no need to diff two structures field
 * by field, and no ambiguity about which fields matter.
 *
 * <h2>Known limit — dynamic ports</h2>
 * A node whose ports depend on its wiring or saved state (the object decomposer, a Discord slash
 * command) reports the shape it <em>currently</em> has, not some fixed default. Computed against a
 * freshly-instantiated node (as {@link NodeCatalog} and a drift check do), that is the type's
 * unconfigured shape; computed against a live node already wired into a graph (as
 * {@code GraphFileIO} does when saving), it is that instance's current shape. The two are not
 * always equal for a dynamic-port node, so a mismatch there is advisory, the same discipline
 * {@code GraphDependencyCheck} applies to an older-than-saved library: worth a look, not a reason
 * to fail a check on its own.
 */
public final class NodeSignature {

    private NodeSignature() {
    }

    /**
     * Computes the fingerprint for one node instance.
     *
     * @param kind the node type's declared {@link NodeKind}, or null when it declared none
     * @param node a node instance — freshly instantiated for a type-level fingerprint, or a live
     *             one already configured, for the shape it currently has
     * @return a 16-character hex fingerprint
     */
    @SuppressWarnings("rawtypes")
    public static String of(NodeKind kind, BaseNode node) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("kind=").append(kind == null ? "" : kind.name()).append('\n');
        for (NodeVariable variable : node.getInputs()) {
            appendVariable(canonical, "in", variable);
        }
        for (NodeVariable variable : node.getOutputs()) {
            appendVariable(canonical, "out", variable);
        }
        for (FlowPort port : node.getFlowInputs()) {
            canonical.append("flowIn:").append(port.name).append('\n');
        }
        for (FlowPort port : node.getFlowOutputs()) {
            canonical.append("flowOut:").append(port.name).append('\n');
        }
        for (String key : new TreeSet<>(node.saveState().keySet())) {
            canonical.append("state:").append(key).append('\n');
        }
        return sha256Hex16(canonical.toString());
    }

    @SuppressWarnings("rawtypes")
    private static void appendVariable(StringBuilder canonical, String direction, NodeVariable variable) {
        canonical.append(direction).append(':').append(variable.name).append(':')
                .append(variable.type.getName()).append(":req=").append(variable.isRequired()).append('\n');
    }

    private static String sha256Hex16(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JDK; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
