package io.github.jaymcole.housegraph.logging;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link LogSink} that forwards records to a Discord channel through an incoming
 * webhook — the first <em>external</em> destination, for watching a machine that nobody is
 * sitting in front of. Configured from the log window; see {@code docs/engine/logging.md}.
 *
 * <h2>Delivery is asynchronous, batched and paced</h2>
 * A sink must not block the thread that logged, and a webhook POST is a network round trip,
 * so {@link #publish} only hands the record to a bounded queue. One daemon worker drains
 * that queue, packs what it finds into a message and posts it. After every post the worker
 * waits {@value #PACING_MILLIS} ms before looking again, which both keeps the sink inside
 * Discord's per-webhook rate limit and turns a burst into one batched message rather than
 * fifty. The first record after a quiet spell still goes out immediately.
 *
 * <p>The queue holds {@value #QUEUE_CAPACITY} records. Past that, new records are
 * <b>dropped</b> rather than made to wait: a slow or unreachable webhook must never become
 * back-pressure on graph execution. Dropped records are counted and the count is reported in
 * the next message that does get through, so a gap is always visible.
 *
 * <h2>It can never feed itself</h2>
 * Anything the delivery path logs is discarded on the way in ({@link #publish} ignores
 * records emitted from the worker thread), and failures are reported to {@code System.err}
 * rather than through {@link Logger} — the same rule {@link FileSink} follows. A webhook
 * that starts failing therefore produces one line on the console, not a widening loop. The
 * failure is reported once per outage, and recovery is reported once too.
 *
 * <h2>What a message looks like</h2>
 * Records are rendered with the same {@link LogFormat} text as the console and the file, and
 * wrapped in a fenced code block so Discord leaves them alone. A message is capped at
 * Discord's {@value #MAX_CONTENT} characters: an over-long batch is split across several
 * messages and a single enormous record is truncated. Mentions are disabled on every post,
 * so a log line containing {@code @everyone} cannot ping a server.
 */
public final class DiscordWebhookSink extends AbstractLogSink implements AutoCloseable {

    /** The sink's display name, and the key its level is remembered under. */
    public static final String SINK_NAME = "Discord";

    /** Discord's hard limit on a webhook message's {@code content}. */
    static final int MAX_CONTENT = 2000;

    /** Records buffered before new ones are dropped. */
    static final int QUEUE_CAPACITY = 1000;

    /** Milliseconds the worker waits after a post before draining again. */
    static final long PACING_MILLIS = 2000;

    /** How long a drain waits for a first record, which also bounds {@link #close()}. */
    private static final long POLL_MILLIS = 200;

    /** How long {@link #close()} waits for the final flush before giving up. */
    private static final int CLOSE_TIMEOUT_SECONDS = 5;

    /** Most records packed into one post, whatever the character budget allows. */
    private static final int MAX_BATCH = 100;

    /** Room left for the ``` fences and the newlines around them. */
    private static final int FENCE_OVERHEAD = 10;

    /** A single record is truncated past this, so one stack trace can't fill a whole message. */
    private static final int MAX_ENTRY = 700;

    /** Hosts a Discord webhook may live on. Anything else is a misconfiguration, not a webhook. */
    private static final Set<String> WEBHOOK_HOSTS = Set.of(
            "discord.com", "www.discord.com", "ptb.discord.com", "canary.discord.com",
            "discordapp.com", "www.discordapp.com");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** One webhook POST, behind a seam so the batching and formatting are testable offline. */
    interface Transport {

        /**
         * Posts one JSON body to the webhook.
         *
         * @param jsonBody the request body
         * @return what Discord answered
         * @throws IOException if the request could not be made
         */
        Response post(String jsonBody) throws IOException;

        /**
         * A webhook's answer, reduced to what delivery reacts to.
         *
         * @param status     the HTTP status code
         * @param retryAfter how long Discord asked us to wait, or {@code null}
         */
        record Response(int status, Duration retryAfter) {
        }
    }

    private final String webhookUrl;
    private final Transport transport;
    private final BlockingQueue<LogRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger dropped = new AtomicInteger();
    private final Thread worker;

    private volatile boolean running = true;
    /** Worker-thread only: whether the last delivery failed, so an outage is reported once. */
    private boolean failing;

    /**
     * Opens a sink posting to a real Discord webhook.
     *
     * @param webhookUrl the webhook URL, as copied from Discord's channel settings
     * @param level      the minimum level forwarded
     * @throws IllegalArgumentException if {@code webhookUrl} is not a Discord webhook URL
     */
    public DiscordWebhookSink(String webhookUrl, LogLevel level) {
        this(webhookUrl, level, null);
    }

    /** Package-private: the same sink with an injected transport, for tests. */
    DiscordWebhookSink(String webhookUrl, LogLevel level, Transport transport) {
        super(SINK_NAME, level);
        this.webhookUrl = requireWebhookUrl(webhookUrl);
        this.transport = transport != null ? transport : body -> httpPost(this.webhookUrl, body);
        this.worker = new Thread(this::drainLoop, "housegraph-discord-log");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Whether {@code url} looks like a Discord incoming webhook — {@code https}, a Discord
     * host, and an {@code /api/webhooks/…} path. Checked before a sink is built so a typo is
     * reported in the settings dialog rather than as a silent 404 an hour later.
     *
     * @param url the candidate URL (may be {@code null})
     * @return {@code true} if it is usable as a webhook
     */
    public static boolean isWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        return WEBHOOK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && path.startsWith("/api/webhooks/")
                && path.length() > "/api/webhooks/".length();
    }

    private static String requireWebhookUrl(String url) {
        if (!isWebhookUrl(url)) {
            throw new IllegalArgumentException("Not a Discord webhook URL: " + url);
        }
        return url.trim();
    }

    /**
     * The webhook being posted to.
     *
     * @return the configured webhook URL
     */
    public String webhookUrl() {
        return webhookUrl;
    }

    @Override
    public void publish(LogRecord record) {
        // Anything the delivery path itself logs is dropped here, so the sink can never feed
        // itself. This is the whole loop guard: the worker is the only thread that posts.
        if (Thread.currentThread() == worker || !running) {
            return;
        }
        if (!queue.offer(record)) {
            // Never block the emitting thread on a slow webhook; count the gap instead.
            dropped.incrementAndGet();
        }
    }

    /**
     * Posts one message immediately on the calling thread, bypassing the queue — what the
     * settings dialog's "Send test message" does, so the user finds out whether the webhook
     * works while they are still looking at the field they typed it into.
     *
     * @param text the message to send
     * @throws IOException if the request failed or Discord rejected it
     */
    public void sendNow(String text) throws IOException {
        Transport.Response response = transport.post(body(text));
        if (!isSuccess(response.status())) {
            throw new IOException("Discord answered HTTP " + response.status());
        }
    }

    /**
     * Stops the worker after it has flushed what is already queued, unpaced. Bounded at
     * {@value #CLOSE_TIMEOUT_SECONDS} seconds: a webhook that has stopped answering delays
     * shutdown by about one request timeout, and never indefinitely. Anything still queued
     * when that runs out is dropped — it is already on the console and in the log file.
     */
    @Override
    public void close() {
        running = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(CLOSE_TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- The worker ---------------------------------------------------------------

    private void drainLoop() {
        while (true) {
            List<LogRecord> batch = nextBatch();
            if (batch.isEmpty()) {
                if (!running) {
                    return;
                }
                continue;
            }
            for (String content : messages(batch, dropped.getAndSet(0))) {
                deliver(content);
            }
            if (!running) {
                // Closing: flush whatever is left back to back. Pacing exists to stay inside the
                // rate limit over a long session, and holding shutdown open for it would cost more
                // than a 429 on the last message does.
                if (queue.isEmpty()) {
                    return;
                }
                continue;
            }
            if (!sleep(PACING_MILLIS)) {
                return;
            }
        }
    }

    /** Waits briefly for a first record, then takes whatever else is already queued with it. */
    private List<LogRecord> nextBatch() {
        List<LogRecord> batch = new ArrayList<>();
        try {
            LogRecord first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (first == null) {
                return batch;
            }
            batch.add(first);
            queue.drainTo(batch, MAX_BATCH - 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
        return batch;
    }

    /** Posts one message, honouring a 429 once, and reporting an outage exactly once. */
    private void deliver(String content) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Transport.Response response = transport.post(body(content));
                if (isSuccess(response.status())) {
                    if (failing) {
                        failing = false;
                        System.err.println("Discord log webhook is reachable again.");
                    }
                    return;
                }
                if (response.status() == 429 && attempt == 0) {
                    if (!running) {
                        // Being rate-limited during the shutdown flush: give up on the rest rather
                        // than hold the process open. These records are already in the log file.
                        return;
                    }
                    Duration wait = response.retryAfter() == null
                            ? Duration.ofSeconds(5) : response.retryAfter();
                    if (!sleep(Math.min(wait.toMillis(), TimeUnit.SECONDS.toMillis(30)))) {
                        return;
                    }
                    continue;
                }
                reportFailure("Discord answered HTTP " + response.status());
                return;
            } catch (IOException | RuntimeException e) {
                reportFailure(e.toString());
                return;
            }
        }
    }

    /**
     * Reports to {@code System.err} — never through {@link Logger}, which would come straight
     * back through this sink — and only on the transition into failure, so an unreachable
     * webhook costs one line rather than one per batch.
     */
    private void reportFailure(String detail) {
        if (!failing) {
            failing = true;
            System.err.println("Discord log webhook delivery failed, suppressing further reports"
                    + " until it recovers: " + detail);
        }
    }

    /** Sleeps unless interrupted or closing; returns false when the worker should stop. */
    private boolean sleep(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (!running && queue.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(Math.min(POLL_MILLIS, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // --- Formatting ---------------------------------------------------------------

    /**
     * Renders a batch as one or more webhook messages, each within Discord's content limit.
     * Package-private so the split, the truncation and the dropped-record notice are testable
     * without a network.
     *
     * @param records      the batch, oldest first
     * @param droppedCount records discarded since the last message, or 0
     * @return the message bodies to post, in order
     */
    static List<String> messages(List<LogRecord> records, int droppedCount) {
        List<String> entries = new ArrayList<>();
        if (droppedCount > 0) {
            entries.add("… " + droppedCount + " log record(s) dropped: the webhook could not keep up.");
        }
        for (LogRecord record : records) {
            entries.add(entry(record));
        }

        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int budget = MAX_CONTENT - FENCE_OVERHEAD;
        for (String entry : entries) {
            if (current.length() > 0 && current.length() + 1 + entry.length() > budget) {
                messages.add(fence(current.toString()));
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(entry);
        }
        if (current.length() > 0) {
            messages.add(fence(current.toString()));
        }
        return messages;
    }

    /** One record as plain text: the shared console/file layout, bounded and fence-safe. */
    private static String entry(LogRecord record) {
        String text = LogFormat.full(record);
        if (text.length() > MAX_ENTRY) {
            text = text.substring(0, MAX_ENTRY) + "… (truncated)";
        }
        // A record containing ``` would otherwise close the code block and let the rest of the
        // batch render as Discord markup.
        return text.replace("```", "ʼʼʼ");
    }

    private static String fence(String body) {
        return "```\n" + body + "\n```";
    }

    /**
     * The webhook request body. Mentions are switched off for every post: a log line is not a
     * reason to notify a whole server.
     */
    private static String body(String content) {
        StringBuilder json = new StringBuilder("{\"content\":\"");
        escape(content, json);
        return json.append("\",\"allowed_mentions\":{\"parse\":[]}}").toString();
    }

    /**
     * Escapes a string into a JSON string literal. Hand-rolled, because the {@code logging}
     * package deliberately depends on nothing but the JDK — see {@code docs/engine/logging.md}.
     */
    private static void escape(String text, StringBuilder out) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static Transport.Response httpPost(String url, String jsonBody) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Transport.Response(response.statusCode(), retryAfter(response));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to the Discord webhook", e);
        }
    }

    /** Discord's {@code Retry-After} is seconds, possibly fractional. */
    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofMillis(Math.round(Double.parseDouble(value.trim()) * 1000));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    @Override
    public String toString() {
        return "DiscordWebhookSink[" + redacted(webhookUrl) + " at " + getLevel() + "]";
    }

    /**
     * The webhook URL with its token removed, so it can appear in a log line or a dialog
     * without leaking the credential that lets anyone post to the channel.
     *
     * @param url a webhook URL
     * @return the same URL with the trailing token replaced by {@code …}
     */
    public static String redacted(String url) {
        Objects.requireNonNull(url, "url");
        int lastSlash = url.lastIndexOf('/');
        return lastSlash < 0 ? "…" : url.substring(0, lastSlash + 1) + "…";
    }
}
