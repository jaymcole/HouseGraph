package io.github.jaymcole.housegraph.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTrustTest {

    private static final String REPOSITORY = "https://github.com/example/housegraph-widgets";

    private static PluginTrust trust(Path temp) {
        return PluginTrust.loadFrom(temp.resolve("plugin-trust.json"));
    }

    @Test
    void aMissingFileTrustsNothingAndInstallsNothing(@TempDir Path temp) {
        PluginTrust trust = trust(temp);

        assertFalse(trust.isAutoInstallEnabled(), "auto-install must be opt-in");
        assertTrue(trust.trustedRepositories().isEmpty());
        assertFalse(trust.isTrustedForInstall(REPOSITORY));
    }

    @Test
    void bothGatesAreRequired(@TempDir Path temp) {
        PluginTrust trust = trust(temp);

        trust.trust(REPOSITORY);
        assertFalse(trust.isTrustedForInstall(REPOSITORY),
                "a trusted repository must still not install while auto-install is off");

        trust.setAutoInstallEnabled(true);
        assertTrue(trust.isTrustedForInstall(REPOSITORY));

        assertFalse(trust.isTrustedForInstall("https://github.com/someone-else/housegraph-widgets"),
                "auto-install being on must not make every repository installable");
    }

    @Test
    void trustSurvivesAReload(@TempDir Path temp) {
        PluginTrust first = trust(temp);
        first.setAutoInstallEnabled(true);
        first.trust(REPOSITORY);

        PluginTrust reloaded = trust(temp);
        assertTrue(reloaded.isAutoInstallEnabled());
        assertEquals(java.util.List.of(REPOSITORY), reloaded.trustedRepositories());
        assertTrue(reloaded.isTrustedForInstall(REPOSITORY + ".git"),
                "a reloaded entry should still match the other spellings");
    }

    @Test
    void revokeMatchesHoweverTheUrlWasSpelled(@TempDir Path temp) {
        PluginTrust trust = trust(temp);
        trust.setAutoInstallEnabled(true);
        trust.trust(REPOSITORY);

        assertTrue(trust.revoke(REPOSITORY + "/"));
        assertFalse(trust.isTrustedForInstall(REPOSITORY));
        assertFalse(trust.revoke(REPOSITORY), "revoking twice should report nothing was removed");
    }

    @Test
    void trustingTheSameRepositoryTwiceAddsOneEntry(@TempDir Path temp) {
        PluginTrust trust = trust(temp);
        trust.trust(REPOSITORY);
        trust.trust(REPOSITORY);

        assertEquals(1, trust.trustedRepositories().size());
    }

    @Test
    void blankUrlsAreIgnored(@TempDir Path temp) {
        PluginTrust trust = trust(temp);
        trust.trust(null);
        trust.trust("   ");

        assertTrue(trust.trustedRepositories().isEmpty());
    }

    @Test
    void aCorruptFileFailsClosed(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("plugin-trust.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        PluginTrust trust = PluginTrust.loadFrom(file);

        // The direction that matters: unreadable must mean "trust nothing", never a half-parsed list.
        assertFalse(trust.isAutoInstallEnabled());
        assertTrue(trust.trustedRepositories().isEmpty());
        assertFalse(trust.isTrustedForInstall(REPOSITORY));
    }

    @Test
    void aFileListingRepositoriesWithAutoInstallOffStillInstallsNothing(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("plugin-trust.json");
        Files.writeString(file, "{\"autoInstall\": false, \"trustedRepositories\": [\"" + REPOSITORY + "\"]}",
                StandardCharsets.UTF_8);

        PluginTrust trust = PluginTrust.loadFrom(file);

        assertEquals(1, trust.trustedRepositories().size());
        assertFalse(trust.isTrustedForInstall(REPOSITORY));
    }
}
