package io.github.jaymcole.housegraph.remote;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} as a child process and hands back what it said.
 *
 * <h2>Why the git binary rather than a library</h2>
 * A Java git implementation (JGit) would be a third dependency in a project that has deliberately
 * kept to two, and it would have to reimplement credential handling, SSH agents and
 * {@code ~/.gitconfig} — all of which the user has already configured for the binary. macOS ships
 * git with the Xcode command line tools. {@code doctor} reports plainly when it is missing, which is
 * a better failure than a library silently disagreeing with the user's own git.
 *
 * <h2>Credentials never go in the command line</h2>
 * {@code argv} is readable by every process on the machine ({@code ps}), so a token embedded in a
 * remote URL leaks to any local user. Environment variables set here reach only the child, which is
 * why {@link #withEnvironment} exists and why the HTTPS path passes a token through
 * {@code GIT_ASKPASS} rather than through the URL. An SSH deploy key avoids the question entirely
 * and is the documented default — see {@code docs/architecture/deployment.md}.
 */
public final class GitCommand {

    private static final Logger log = Log.get(GitCommand.class);

    /**
     * How long any single git invocation may take. Generous enough for a first clone over a slow
     * link, bounded so a network black hole stalls one sync rather than wedging the daemon: the
     * poll loop must always come back round.
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /** What one invocation produced. A non-zero {@link #exitCode} is data, not an exception. */
    public record Result(int exitCode, String stdout, String stderr) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        /** The first non-blank line of output, which is all most callers want. */
        public String firstLine() {
            for (String line : stdout.split("\\R")) {
                if (!line.isBlank()) {
                    return line.trim();
                }
            }
            return "";
        }

        /**
         * This result, or an exception naming what failed.
         *
         * @param what what was being attempted, for the message
         * @return this result when git succeeded
         */
        public Result orThrow(String what) {
            if (succeeded()) {
                return this;
            }
            String detail = stderr.isBlank() ? stdout : stderr;
            throw new GitException(what + " failed (git exit " + exitCode + ")"
                    + (detail.isBlank() ? "" : ": " + detail.strip()));
        }
    }

    /** Raised for a git failure worth showing the user verbatim. */
    public static class GitException extends RuntimeException {
        public GitException(String message) {
            super(message);
        }

        public GitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final long timeoutSeconds;

    private GitCommand(Path workingDirectory, Map<String, String> environment, long timeoutSeconds) {
        this.workingDirectory = workingDirectory;
        this.environment = Map.copyOf(environment);
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Runs git in {@code workingDirectory}; pass null to inherit this process's. */
    public static GitCommand in(Path workingDirectory) {
        return new GitCommand(workingDirectory, Map.of(), DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * This command with extra environment variables for the child.
     *
     * @param extra variables to add; values are never logged
     * @return a new command — this type is immutable so one configured instance can be shared
     */
    public GitCommand withEnvironment(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(environment);
        merged.putAll(extra);
        return new GitCommand(workingDirectory, merged, timeoutSeconds);
    }

    /** This command with a different per-invocation timeout. */
    public GitCommand withTimeoutSeconds(long seconds) {
        return new GitCommand(workingDirectory, environment, seconds);
    }

    /**
     * Runs {@code git <arguments>} and waits for it.
     *
     * @param arguments the git subcommand and its arguments, without the leading {@code git}
     * @return what it printed and its exit code
     * @throws GitException if git can't be started, times out, or the wait is interrupted
     */
    public Result run(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);

        log.debug("git {}", String.join(" ", arguments));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitException("Could not run git — is it installed and on the PATH?", e);
        }

        // Both pipes must be drained *concurrently*. A child that fills its stderr buffer while this
        // thread is blocked reading stdout deadlocks forever — and that hang looks exactly like a
        // slow network, so it would only ever show up on the one repository with a lot to say.
        StringBuilder errorText = new StringBuilder();
        Thread errorPump = new Thread(() -> {
            try {
                errorText.append(drain(process.getErrorStream()));
            } catch (IOException e) {
                // The exit code and stdout still tell the caller what happened; losing the
                // diagnostic text is not worth failing an otherwise successful command over.
                log.debug("Could not read git's stderr", e);
            }
        }, "git-stderr");
        errorPump.setDaemon(true);
        errorPump.start();

        try {
            String stdout = drain(process.getInputStream());
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitException("git " + arguments[0] + " timed out after " + timeoutSeconds + "s");
            }
            errorPump.join(TimeUnit.SECONDS.toMillis(5));
            return new Result(process.exitValue(), stdout, errorText.toString());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("Interrupted while running git " + arguments[0], e);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new GitException("Could not read output of git " + arguments[0], e);
        }
    }

    /** Whether a usable git binary exists, for {@code doctor} and for a clear startup failure. */
    public static boolean isAvailable() {
        try {
            return in(null).withTimeoutSeconds(10).run("--version").succeeded();
        } catch (GitException e) {
            return false;
        }
    }

    private static String drain(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
