package io.github.jaymcole.housegraph.graph;

public enum NodeProcessingStatus {
    NOT_STARTED,
    /** Currently being resolved/executed during the current pass; also doubles as the cycle-detection marker. */
    IN_PROGRESS,
    SUCCESS,
    FAILED;

    public boolean isComplete() {
        return this == SUCCESS || this == FAILED;
    }
}
