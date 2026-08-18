package io.github.jaymcole.housegraph.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModeTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("housegraph.daemon");
    }

    @Test
    void isNotDaemonByDefault() {
        System.clearProperty("housegraph.daemon");
        assertFalse(RuntimeMode.isDaemon());
    }

    @Test
    void isDaemonWhenThePropertyIsSet() {
        System.setProperty("housegraph.daemon", "true");
        assertTrue(RuntimeMode.isDaemon());
    }
}
