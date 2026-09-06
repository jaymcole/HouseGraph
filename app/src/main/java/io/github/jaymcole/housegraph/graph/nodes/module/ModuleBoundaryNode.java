package io.github.jaymcole.housegraph.graph.nodes.module;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The half every module boundary marker shares: a declared <b>name</b>, the field that edits it,
 * and the rule that a marker without a name is misconfigured.
 *
 * <h2>What a boundary node is</h2>
 * A named marker sitting on the edge of a graph, carrying exactly one port. It computes nothing.
 * Its whole content is the declaration — this name, and on the data pair a type — which is what
 * lets a graph be described by an interface rather than only by its contents.
 *
 * <h2>The name is the binding key, so it is stored verbatim</h2>
 * The declared name is not a label. It is intended to become a port name where the graph is used,
 * and {@code docs/engine/save-format.md} binds edge endpoints by port name wherever the name is
 * non-blank and unique on the node — an endpoint whose name changed between saves is dropped with
 * a warning rather than mis-wired. So the name is persisted exactly as typed: not trimmed, not
 * case-folded, not slugified. A normalisation applied here would silently rename ports for
 * everyone downstream of it, and a normalisation applied inconsistently would drop their edges.
 * <p>
 * A blank name has no binding key at all, which is why {@link #isMisconfigured()} reports it — the
 * same treatment {@code MissingNode} uses for a node that cannot do its job. Whether two markers
 * <em>share</em> a name is deliberately not checked here: it is a fact about a pair of nodes in
 * one graph, not about either node alone, and a marker cannot see its siblings. {@link
 * #getDeclaredName()} is public so whatever validates a graph as a module can check it in one
 * pass and say which nodes collide.
 *
 * <h2>No view is the ordinary case</h2>
 * These markers are meant to be read from inside a graph that nothing is drawing, so the name
 * field is null except while something has this node on screen. Every write to it goes through
 * {@link #present(Runnable)}, which discards the update when there is no view.
 */
public abstract class ModuleBoundaryNode extends BaseNode implements NodeContentProvider {

    /** The key {@link #saveState()} writes the declared name under. */
    private static final String NAME_KEY = "name";

    /**
     * The declared name, never null and stored exactly as authored. Read from execution threads
     * (through {@link #isMisconfigured()} and save/load) and written from the FX thread.
     */
    private volatile String declaredName = "";

    /** The name field, or null whenever nothing is drawing this node. */
    private TextField nameField;

    /**
     * The name this marker declares, exactly as it was typed or loaded — empty, never null, when
     * nothing has been declared.
     *
     * @return the declared name, possibly empty
     */
    public final String getDeclaredName() {
        return declaredName;
    }

    /**
     * Sets the declared name and syncs the name field if one is drawn. Stores the text verbatim;
     * a null is read as "nothing declared". Renaming does not touch this node's port, whose name
     * is fixed, so wiring inside the graph survives a rename untouched.
     *
     * @param name the name to declare, or null to clear it
     */
    public final void setDeclaredName(String name) {
        String updated = name == null ? "" : name;
        if (updated.equals(declaredName)) {
            return;
        }
        declaredName = updated;
        present(() -> {
            if (nameField != null && !nameField.getText().equals(declaredName)) {
                nameField.setText(declaredName);
            }
        });
    }

    /**
     * Does nothing. A boundary marker carries a value or a control signal across the edge of a
     * graph; it does not transform either, so there is nothing to compute when it is resolved or
     * reached. Its port's value is committed by the engine after this returns exactly as it does
     * for any other node, which is what makes the value readable from outside afterwards.
     *
     * @param ctx this invocation's context, unused
     */
    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    /**
     * True when this marker declares no name, on top of the usual unsatisfied-required-input
     * check. A nameless marker has nothing for an edge to bind to where the graph is used, so it
     * cannot do its job — the same reason {@code MissingNode} overrides this.
     *
     * @return true if the declared name is blank, or a required input has no value source
     */
    @Override
    public boolean isMisconfigured() {
        return declaredName.isBlank() || super.isMisconfigured();
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new LinkedHashMap<>();
        if (!declaredName.isEmpty()) {
            state.put(NAME_KEY, declaredName);
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        setDeclaredName(state.get(NAME_KEY));
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        nameField = new TextField(declaredName);
        nameField.setPromptText("Name (required)");
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.textProperty().addListener((observable, was, now) -> setDeclaredName(now));

        VBox content = new VBox(4, labelled("Name", nameField));
        content.setFillWidth(true);
        addExtraContent(content);
        return content;
    }

    /**
     * Hook for a subclass to append its own rows beneath the name field. Called from
     * {@link #createNodeContent()} on the FX thread, once, while the view is being built.
     *
     * @param content the column the name field was just added to
     */
    protected void addExtraContent(VBox content) {
    }

    /** A caption above a control, so a two-row boundary node says which field is which. */
    static VBox labelled(String caption, javafx.scene.Node control) {
        Label label = new Label(caption);
        label.setStyle("-fx-font-size: 10px;");
        VBox box = new VBox(1, label, control);
        box.setFillWidth(true);
        return box;
    }
}
