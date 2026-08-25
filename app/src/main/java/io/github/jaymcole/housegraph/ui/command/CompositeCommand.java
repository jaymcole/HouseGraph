package io.github.jaymcole.housegraph.ui.command;

import java.util.List;

/**
 * Bundles several already-applied {@link Command}s into a single undo step, so a
 * gesture that touches more than one kind of state — e.g. a drag that moves nodes
 * and carries along rubber-band-selected edge waypoints — undoes and redoes as one
 * user-visible action. Undo runs the parts in reverse order, in case a later part
 * depended on an earlier one having already happened.
 */
public class CompositeCommand implements Command {

    private final List<Command> commands;

    public CompositeCommand(List<Command> commands) {
        this.commands = List.copyOf(commands);
    }

    @Override
    public void execute() {
        for (Command command : commands) {
            command.execute();
        }
    }

    @Override
    public void undo() {
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undo();
        }
    }
}
