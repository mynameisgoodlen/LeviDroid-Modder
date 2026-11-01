package org.levimc.launcher.core.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiscordOAuthTest {

    @Test
    public void codeChallengeMatchesPkceVector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DiscordOAuth.codeChallenge(verifier));
    }

    @Test
    public void generatedVerifierIsUrlSafeWithExpectedLength() {
        for (int i = 0; i < 20; i++) {
            String verifier = DiscordOAuth.generateCodeVerifier();
            assertEquals(43, verifier.length());
            assertFalse(verifier.contains("+"));
            assertFalse(verifier.contains("/"));
            assertFalse(verifier.contains("="));
        }
    }

    @Test
    public void authorizeUrlContainsRequiredParams() {
        String url = DiscordOAuth.buildAuthorizeUrl(
                "1234567890123456789",
                "http://127.0.0.1:8080/callback",
                "challenge-value",
                "state-value");

        assertTrue(url.startsWith(DiscordOAuth.AUTHORIZE_URL + "?"));
        assertTrue(url.contains("client_id=1234567890123456789"));
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2Fcallback"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("scope=openid+sdk.social_layer_presence"));
        assertTrue(url.contains("code_challenge=challenge-value"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=state-value"));
    }
}
