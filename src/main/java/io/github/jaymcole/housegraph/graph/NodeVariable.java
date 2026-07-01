package io.github.jaymcole.housegraph.graph;

import java.util.UUID;

public class NodeVariable<T> {

    public final String name;
    public final String id;
    private T value;

    public NodeVariable(String variableName, String variableId) {
        this.name = variableName;
        this.id = variableId;
    }

    public NodeVariable(String variableName) {
        this.name = variableName;
        id = UUID.randomUUID().toString();
    }

    public void setValue(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

}
