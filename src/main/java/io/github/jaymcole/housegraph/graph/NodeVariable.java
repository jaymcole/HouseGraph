package io.github.jaymcole.housegraph.graph;

import java.util.UUID;

public class NodeVariable<T> {

    public final String name;
    public final String id;
    public final Class<T> type;
    public final boolean manuallyEditable;
    private T value;

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

    public void setValue(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

}
