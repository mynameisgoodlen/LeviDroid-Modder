package org.levimc.launcher.core.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DiscordAuthStoreTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingFileLoadsAsNull() {
        assertNull(DiscordAuthStore.load(temporaryFolder.getRoot()));
    }

    @Test
    public void saveAndLoadRoundTripPreservesTokens() throws Exception {
        File dir = temporaryFolder.newFolder("tokens");
        DiscordTokens tokens = new DiscordTokens();
        tokens.accessToken = "access-token";
        tokens.refreshToken = "refresh-token";
        tokens.tokenType = "Bearer";
        tokens.expiresIn = 604800L;
        tokens.obtainedAt = 1234567890L;

        DiscordAuthStore.save(dir, tokens);
        DiscordTokens loaded = DiscordAuthStore.load(dir);

        assertNotNull(loaded);
        assertEquals("access-token", loaded.accessToken);
        assertEquals("refresh-token", loaded.refreshToken);
        assertEquals("Bearer", loaded.tokenType);
        assertEquals(604800L, loaded.expiresIn);
        assertEquals(1234567890L, loaded.obtainedAt);
    }

    @Test
    public void corruptFileLoadsAsNull() throws Exception {
        File dir = temporaryFolder.newFolder("corrupt");
        Files.write(new File(dir, "discord_rpc_tokens.json").toPath(), "{bad json".getBytes(StandardCharsets.UTF_8));
        assertNull(DiscordAuthStore.load(dir));
    }

    @Test
    public void clearRemovesStoredTokens() throws Exception {
        File dir = temporaryFolder.newFolder("clear");
        DiscordTokens tokens = new DiscordTokens();
        tokens.accessToken = "access";
        tokens.refreshToken = "refresh";
        DiscordAuthStore.save(dir, tokens);

        DiscordAuthStore.clear(dir);

        assertNull(DiscordAuthStore.load(dir));
    }

    @Test
    public void expiryUsesOneMinuteSkew() {
        DiscordTokens tokens = new DiscordTokens();
        tokens.expiresIn = 300L;
        tokens.obtainedAt = System.currentTimeMillis() - 250_000L;
        assertTrue(tokens.isExpired());

        tokens.obtainedAt = System.currentTimeMillis();
        assertFalse(tokens.isExpired());
    }

    @Test
    public void bearerTokenDefaultsToBearerType() {
        DiscordTokens tokens = new DiscordTokens();
        tokens.accessToken = "abc";
        assertEquals("Bearer abc", tokens.bearerToken());

        tokens.tokenType = "Bearer";
        assertEquals("Bearer abc", tokens.bearerToken());
    }
}
