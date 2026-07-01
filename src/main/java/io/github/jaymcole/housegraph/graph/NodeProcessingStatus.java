package io.github.jaymcole.housegraph.graph;

public enum NodeProcessingStatus {
    NOT_STARTED,
    SUCCESS,
    WAITING_FOR_UPSTREAM,
    FAILED;

    public boolean isComplete() {
        return this == SUCCESS || this == FAILED;
    }
}
