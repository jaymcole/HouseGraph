package io.github.jaymcole.housegraph.graph;

import java.util.UUID;

public class NodeVariable<T> {

    public final String name;
    public final String id;
    public final Class<T> type;
    public final boolean manuallyEditable;
    private T value;
    private boolean secret = false;
    private boolean transientValue = false;
    private boolean required = false;

    public NodeVariable(String variableName, Class<T> type, String variableId, boolean manuallyEditable) {
        this.name = variableName;
        this.type = type;
        this.id = variableId;
        this.manuallyEditable = manuallyEditable;
    }

    public NodeVariable(String variableName, Class<T> type, boolean manuallyEditable) {
        this(variableName, type, UUID.randomUUID().toString(), manuallyEditable);
    }

    public NodeVariable(String variableName, Class<T> type) {
        this(variableName, type, UUID.randomUUID().toString(), false);
    }

    /**
     * Sets this variable's value. During a run (an {@link ExecutionContext} is bound to the
     * current thread) the value is written into that run's computed-value overlay, keeping
     * concurrent runs isolated; outside a run it's stored on the variable as its authored value.
     *
     * @param value the value to set
     */
    public void setValue(T value) {
        ExecutionContext context = ExecutionContext.current();
        if (context != null) {
            context.setValue(this, value);
        } else {
            this.value = value;
        }
    }

    /**
     * This variable's value: the current run's computed value if it has one, otherwise the
     * authored value stored on the variable. Outside a run, always the authored value. See
     * {@link ExecutionContext}.
     *
     * @return the effective value for the current thread
     */
    public T getValue() {
        ExecutionContext context = ExecutionContext.current();
        if (context != null && context.hasValue(this)) {
            return context.getValue(this);
        }
        return value;
    }

    /**
     * Writes a run's computed value straight onto the variable (bypassing the overlay), so it's
     * visible to readers with no context bound. Package-private, called only by
     * {@link ExecutionContext#commitValuesOf} after a node's {@code process()}. See that class.
     *
     * @param computed the value computed during the run
     */
    @SuppressWarnings("unchecked")
    void commitComputed(Object computed) {
        this.value = (T) computed;
    }

    /**
     * Marks this variable as holding a secret, so persistence never writes its value to
     * disk (see the graph's file IO). Fluent, for use at field initialisation:
     * {@code new NodeVariable<>("Value", String.class).markSecret()}.
     *
     * @return this variable, for chaining
     */
    public NodeVariable<T> markSecret() {
        this.secret = true;
        return this;
    }

    /**
     * Whether this variable's value must be kept out of save files.
     *
     * @return true if this variable holds a secret
     */
    public boolean isSecret() {
        return secret;
    }

    /**
     * Marks this variable as holding a live runtime object that only makes sense during
     * one execution pass (e.g. a Discord reply handle) and must never be persisted.
     * Like {@link #markSecret()}, its value is written as null to save files. Fluent.
     *
     * @return this variable, for chaining
     */
    public NodeVariable<T> transientValue() {
        this.transientValue = true;
        return this;
    }

    /**
     * Whether this variable's value is runtime-only and must not be written to save files.
     *
     * @return true if this variable holds a transient runtime value
     */
    public boolean isTransient() {
        return transientValue;
    }

    /**
     * Marks this input variable as <em>required</em>: the node is considered misconfigured
     * (and flagged as such in the UI) until this input has a value source — either an incoming
     * data edge or a non-null manually-authored value. Node authors set the <em>default</em> at
     * field initialisation, alongside the other flags:
     * {@code new NodeVariable<>("A", Float.class).required()}. Optional by default, so no
     * existing node becomes misconfigured until its author opts in. The user can override this
     * per-input from the node's right-click menu (see the node view), and that choice
     * <em>is</em> persisted (see the graph's file IO). Fluent, for use as the author default.
     * See {@link BaseNode#getUnsatisfiedRequiredInputs()}.
     *
     * @return this variable, for chaining
     */
    public NodeVariable<T> required() {
        this.required = true;
        return this;
    }

    /**
     * Sets whether this input is required. The non-fluent counterpart to {@link #required()},
     * for a user toggling it from the UI or a load restoring a saved choice.
     *
     * @param required whether this input must have a value source
     */
    public void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * Whether this input must have a value source (an incoming edge or a manual value) for its
     * node to be considered configured.
     *
     * @return true if this input is required
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Whether this variable's value is written to save files. Only manually-authored values
     * (a constant, a typed-in config field) persist; everything a node <em>computes</em> is
     * left out and recomputed on the next run instead. This "don't save computed values"
     * default is deliberate: it keeps stale/derived data out of the file, means a value that
     * can't be serialised (e.g. a non-finite float a decomposer read off some object) never
     * reaches the writer, and — with the {@link #markSecret() secret}/{@link #transientValue()
     * transient} exclusions still applied on top — makes it impossible for a secret to slip
     * to disk just because some node happened to expose it as an output.
     *
     * @return true if this variable's value should be written to save files
     */
    public boolean isPersistentValue() {
        return manuallyEditable && !secret && !transientValue;
    }

}
