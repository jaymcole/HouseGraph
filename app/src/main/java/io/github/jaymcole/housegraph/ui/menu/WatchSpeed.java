package io.github.jaymcole.housegraph.ui.menu;

import java.util.Optional;

/**
 * The step delays Run ▸ Watch Speed offers, as {@code NodeGraph.setStepDelayMillis} values.
 *
 * <h2>Why a short list</h2>
 * A handful of round numbers rather than a slider: the useful range spans a factor of ten and the
 * exact figure never matters, only whether a run crawls or flies.
 *
 * <h2>Why it is not private to the menu</h2>
 * Two things offer this choice — the per-window Run menu, and the preferences window setting the
 * speed a window starts at. They have to offer the same list, or a saved default could be a value
 * the menu cannot show as selected.
 */
public enum WatchSpeed {

    OFF("Off", 0),
    QUARTER_SECOND("0.25s", 250),
    HALF_SECOND("0.5s", 500),
    ONE_SECOND("1s", 1000),
    TWO_SECONDS("2s", 2000);

    private final String label;
    private final long millis;

    WatchSpeed(String label, long millis) {
        this.label = label;
        this.millis = millis;
    }

    /**
     * The menu/dropdown text for this speed.
     *
     * @return the human label
     */
    public String label() {
        return label;
    }

    /**
     * The step delay this speed applies.
     *
     * @return the delay in milliseconds
     */
    public long millis() {
        return millis;
    }

    /**
     * The speed matching an exact delay.
     *
     * <h4>Why this can be empty</h4>
     * {@code preferences.json} is editable by hand, so a saved delay need not be one of these.
     * Such a value is still applied to the graph — it is a valid delay — but no radio item is
     * selected for it, which is the honest rendering of "not one of the offered speeds".
     *
     * @param millis a step delay
     * @return the matching speed, or empty when none matches exactly
     */
    public static Optional<WatchSpeed> forMillis(long millis) {
        for (WatchSpeed speed : values()) {
            if (speed.millis == millis) {
                return Optional.of(speed);
            }
        }
        return Optional.empty();
    }
}
