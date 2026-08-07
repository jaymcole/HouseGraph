package io.github.jaymcole.housegraph;

import javafx.application.Application;

/**
 * Plain (non-JavaFX) entry point.
 * <p>
 * Launching JavaFX from a {@code main} that lives in a class which does not
 * itself extend {@link Application} avoids the "JavaFX runtime components are
 * missing" error when the app is started from a plain classpath jar.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
