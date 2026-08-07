package io.github.jaymcole.housegraph.discord;

/**
 * Matches an incoming Discord message against a command trigger (e.g. {@code "!deploy"})
 * and extracts its arguments. A message invokes a trigger when it starts with that
 * trigger (case-insensitively) followed by end-of-message or whitespace — so
 * {@code "!deploy"} matches {@code "!deploy prod"} but not {@code "!deployment"}.
 */
public final class CommandMatcher {

    private CommandMatcher() {
    }

    public static boolean matches(String message, String trigger) {
        if (message == null || trigger == null || trigger.isBlank()) {
            return false;
        }
        String stripped = message.strip();
        if (stripped.length() < trigger.length()) {
            return false;
        }
        if (!stripped.regionMatches(true, 0, trigger, 0, trigger.length())) {
            return false;
        }
        return stripped.length() == trigger.length() || Character.isWhitespace(stripped.charAt(trigger.length()));
    }

    /** The argument text after {@code trigger}, trimmed — or empty if the message doesn't invoke it. */
    public static String args(String message, String trigger) {
        if (!matches(message, trigger)) {
            return "";
        }
        return message.strip().substring(trigger.length()).strip();
    }
}
