package io.github.jaymcole.housegraph.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the Discord destination decides on its own: which URLs it accepts, how a batch
 * is packed into messages that fit Discord's limit, that records reach the transport without
 * the emitting thread waiting on it, and that the delivery path cannot feed itself. The
 * transport is faked throughout — nothing here touches the network.
 */
class DiscordWebhookSinkTest {

    private static final String URL = "https://discord.com/api/webhooks/123456/abcdef";

    /** Records every body posted, and answers 204 as Discord does. */
    private static final class RecordingTransport implements DiscordWebhookSink.Transport {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final CountDownLatch posted = new CountDownLatch(1);

        @Override
        public Response post(String jsonBody) {
            bodies.add(jsonBody);
            posted.countDown();
            return new Response(204, null);
        }
    }

    private static LogRecord record(LogLevel level, String message) {
        return new LogRecord(Instant.EPOCH, level, "Test", "main", message, null);
    }

    // --- URL validation -----------------------------------------------------------

    @Test
    void acceptsARealWebhookUrlAndRejectsEverythingElse() {
        assertTrue(DiscordWebhookSink.isWebhookUrl(URL));
        assertTrue(DiscordWebhookSink.isWebhookUrl("https://canary.discord.com/api/webhooks/1/t"));
        assertTrue(DiscordWebhookSink.isWebhookUrl("https://discordapp.com/api/webhooks/1/t"));

        assertFalse(DiscordWebhookSink.isWebhookUrl(null));
        assertFalse(DiscordWebhookSink.isWebhookUrl("  "));
        assertFalse(DiscordWebhookSink.isWebhookUrl("http://discord.com/api/webhooks/1/t"), "plaintext");
        assertFalse(DiscordWebhookSink.isWebhookUrl("https://example.com/api/webhooks/1/t"), "wrong host");
        assertFalse(DiscordWebhookSink.isWebhookUrl("https://discord.com/api/webhooks/"), "no webhook named");
        assertFalse(DiscordWebhookSink.isWebhookUrl("https://discord.com/channels/1/2"), "wrong path");
        assertFalse(DiscordWebhookSink.isWebhookUrl("not a url at all"));
    }

    @Test
    void refusesToBuildOnANonWebhookUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookSink("https://example.com/hook", LogLevel.WARN));
    }

    @Test
    void redactionKeepsThePathButDropsTheToken() {
        assertEquals("https://discord.com/api/webhooks/123456/…", DiscordWebhookSink.redacted(URL));
    }

    // --- Message shaping ----------------------------------------------------------

    @Test
    void aBatchBecomesOneFencedMessage() {
        List<String> messages = DiscordWebhookSink.messages(
                List.of(record(LogLevel.WARN, "first"), record(LogLevel.ERROR, "second")), 0);

        assertEquals(1, messages.size());
        String only = messages.get(0);
        assertTrue(only.startsWith("```\n") && only.endsWith("\n```"), "fenced: " + only);
        assertTrue(only.contains("first") && only.contains("second"));
        assertTrue(only.contains("WARN") && only.contains("ERROR"));
    }

    @Test
    void anOverlongBatchIsSplitAcrossMessagesThatEachFit() {
        List<LogRecord> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(record(LogLevel.INFO, "message number " + i + " " + "x".repeat(100)));
        }

        List<String> messages = DiscordWebhookSink.messages(many, 0);

        assertTrue(messages.size() > 1, "a 60-record batch should not fit in one message");
        for (String message : messages) {
            assertTrue(message.length() <= DiscordWebhookSink.MAX_CONTENT,
                    "message of " + message.length() + " chars exceeds Discord's limit");
        }
        assertTrue(messages.get(messages.size() - 1).contains("message number 59"), "nothing is lost");
    }

    @Test
    void oneEnormousRecordIsTruncatedRatherThanDropped() {
        List<String> messages = DiscordWebhookSink.messages(
                List.of(record(LogLevel.ERROR, "y".repeat(10_000))), 0);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).length() <= DiscordWebhookSink.MAX_CONTENT);
        assertTrue(messages.get(0).contains("(truncated)"));
    }

    @Test
    void aRecordCannotBreakOutOfTheCodeFence() {
        List<String> messages = DiscordWebhookSink.messages(
                List.of(record(LogLevel.WARN, "closing ``` then **bold**")), 0);

        // Exactly two fences — the ones this class wrote — so the rest renders as preformatted text.
        assertEquals(2, countOccurrences(messages.get(0), "```"));
    }

    @Test
    void droppedRecordsAreAnnouncedInTheNextMessage() {
        List<String> messages = DiscordWebhookSink.messages(List.of(record(LogLevel.WARN, "after the gap")), 7);

        assertTrue(messages.get(0).contains("7 log record(s) dropped"), messages.get(0));
    }

    // --- Delivery -----------------------------------------------------------------

    @Test
    void aPublishedRecordReachesTheTransportAsJson() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        try (DiscordWebhookSink sink = new DiscordWebhookSink(URL, LogLevel.INFO, transport)) {
            sink.publish(record(LogLevel.WARN, "disk is nearly full"));

            assertTrue(transport.posted.await(5, TimeUnit.SECONDS), "nothing was posted");
            String body = transport.bodies.get(0);
            assertTrue(body.contains("disk is nearly full"), body);
            assertTrue(body.contains("\"allowed_mentions\":{\"parse\":[]}}"),
                    "a log line must never be able to ping a server: " + body);
        }
    }

    @Test
    void quotesAndNewlinesAreEscapedIntoValidJson() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        try (DiscordWebhookSink sink = new DiscordWebhookSink(URL, LogLevel.INFO, transport)) {
            sink.publish(record(LogLevel.WARN, "said \"hello\"\tand\\left"));

            assertTrue(transport.posted.await(5, TimeUnit.SECONDS), "nothing was posted");
            String body = transport.bodies.get(0);
            assertTrue(body.contains("said \\\"hello\\\"\\tand\\\\left"), body);
            // The whole payload is one line: every real newline became an escape.
            assertFalse(body.contains("\n"), "raw newline in JSON body: " + body);
        }
    }

    @Test
    void whatTheDeliveryPathLogsIsDiscardedRatherThanQueued() throws Exception {
        CountDownLatch firstPost = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();
        DiscordWebhookSink[] holder = new DiscordWebhookSink[1];

        DiscordWebhookSink.Transport looping = body -> {
            bodies.add(body);
            // Exactly what an HTTP client logging through the same pipeline would do.
            holder[0].publish(record(LogLevel.ERROR, "posted from inside the transport"));
            firstPost.countDown();
            return new DiscordWebhookSink.Transport.Response(204, null);
        };

        holder[0] = new DiscordWebhookSink(URL, LogLevel.INFO, looping);
        try (DiscordWebhookSink sink = holder[0]) {
            sink.publish(record(LogLevel.WARN, "the only real record"));
            assertTrue(firstPost.await(5, TimeUnit.SECONDS), "nothing was posted");

            // Long enough for a second batch to have gone out if the loop guard were missing.
            Thread.sleep(DiscordWebhookSink.PACING_MILLIS + 1000);
            assertEquals(1, bodies.size(), "the sink fed itself: " + bodies);
        }
    }

    @Test
    void aFullQueueDropsRecordsInsteadOfBlockingTheEmitter() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstPost = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();

        DiscordWebhookSink.Transport slow = body -> {
            bodies.add(body);
            if (firstPost.getCount() > 0) {
                firstPost.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new DiscordWebhookSink.Transport.Response(204, null);
        };

        try (DiscordWebhookSink sink = new DiscordWebhookSink(URL, LogLevel.INFO, slow)) {
            sink.publish(record(LogLevel.WARN, "kick the worker awake"));
            assertTrue(firstPost.await(5, TimeUnit.SECONDS), "the worker never started posting");

            // The webhook is stuck; none of this can be allowed to make the emitter wait.
            long start = System.nanoTime();
            int overflow = 50;
            for (int i = 0; i < DiscordWebhookSink.QUEUE_CAPACITY + overflow; i++) {
                sink.publish(record(LogLevel.WARN, "burst " + i));
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMillis < 5000, "publishing blocked for " + elapsedMillis + "ms");

            release.countDown();
            assertTrue(waitFor(() -> bodies.stream().anyMatch(b -> b.contains("dropped")), 15),
                    "the gap was never announced: " + bodies.size() + " message(s) posted");
        }
    }

    /** Polls {@code condition} until it holds or {@code seconds} elapse. */
    private static boolean waitFor(java.util.function.BooleanSupplier condition, int seconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
