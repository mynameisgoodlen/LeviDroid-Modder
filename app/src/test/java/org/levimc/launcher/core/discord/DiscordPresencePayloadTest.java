package org.levimc.launcher.core.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class DiscordPresencePayloadTest {

    @Test
    public void activityMapContainsGatewayFields() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setSessionId("session-1");
        payload.setName("LeviLauncher Modder");
        payload.setDetails("Browsing homepage");
        payload.setStartTimestamp(1700000000000L);
        payload.setLargeImage("large-asset");
        payload.setLargeText("LeviLauncher");

        Map<String, Object> activity = payload.toActivityMap();

        assertEquals("LeviLauncher Modder", activity.get("name"));
        assertEquals(0, activity.get("type"));
        assertEquals("app-1", activity.get("application_id"));
        assertEquals("session-1", activity.get("session_id"));
        assertEquals("Browsing homepage", activity.get("details"));

        Map<String, Object> timestamps = (Map<String, Object>) activity.get("timestamps");
        assertEquals(1700000000000L, timestamps.get("start"));

        Map<String, Object> assets = (Map<String, Object>) activity.get("assets");
        assertEquals("large-asset", assets.get("large_image"));
        assertEquals("LeviLauncher", assets.get("large_text"));
    }

    @Test
    public void nullFieldsAreStripped() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setName("Game");

        Map<String, Object> activity = payload.toActivityMap();

        assertFalse(activity.containsKey("details"));
        assertFalse(activity.containsKey("state"));
        assertFalse(activity.containsKey("assets"));
        assertFalse(activity.containsKey("buttons"));
        assertFalse(activity.containsKey("metadata"));

        Map<String, Object> timestamps = (Map<String, Object>) activity.get("timestamps");
        assertFalse(timestamps.containsKey("start"));
        assertFalse(timestamps.containsKey("end"));
    }

    @Test
    public void buttonsUseLegacyGatewayShape() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setName("Game");
        payload.addButton("Open", "https://example.com");

        Map<String, Object> activity = payload.toActivityMap();

        assertEquals(List.of("Open"), activity.get("buttons"));
        Map<String, Object> metadata = (Map<String, Object>) activity.get("metadata");
        assertEquals(List.of("https://example.com"), metadata.get("button_urls"));
    }

    @Test
    public void moreThanTwoButtonsAreIgnored() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setName("Game");
        payload.addButton("One", "https://example.com/1");
        payload.addButton("Two", "https://example.com/2");
        payload.addButton("Three", "https://example.com/3");

        Map<String, Object> activity = payload.toActivityMap();

        assertEquals(List.of("One", "Two"), activity.get("buttons"));
    }

    @Test
    public void externalUrlIsRejectedUntilRegistered() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setLargeImage("https://example.com/icon.png");
        try {
            payload.toActivityMap();
            fail("expected INVALID_URL");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void imageKeysPassThroughParseRules() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setLargeImage("external/abc/icon.png");
        payload.setSmallImage("1234567890123456789");

        Map<String, Object> activity = payload.toActivityMap();

        Map<String, Object> assets = (Map<String, Object>) activity.get("assets");
        assertEquals("mp:external/abc/icon.png", assets.get("large_image"));
        assertEquals("1234567890123456789", assets.get("small_image"));
    }

    @Test
    public void discordCdnUrlIsConvertedToMpPrefix() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setLargeImage("https://cdn.discordapp.com/app-assets/1/icon.png");

        Map<String, Object> activity = payload.toActivityMap();

        Map<String, Object> assets = (Map<String, Object>) activity.get("assets");
        assertEquals("mp:app-assets/1/icon.png", assets.get("large_image"));
    }

    @Test
    public void plainAssetKeysPassThrough() {
        PresencePayload payload = new PresencePayload("app-1");
        payload.setLargeImage("logo");

        Map<String, Object> activity = payload.toActivityMap();

        Map<String, Object> assets = (Map<String, Object>) activity.get("assets");
        assertEquals("logo", assets.get("large_image"));
    }

    @Test
    public void presenceWrapperCarriesActivitiesAndStatus() {
        Map<String, Object> presence = PresencePayload.presence(
                List.of(new PresencePayload("app-1").toActivityMap()),
                "online",
                false);

        assertEquals("online", presence.get("status"));
        assertEquals(0, presence.get("since"));
        assertEquals(false, presence.get("afk"));

        List<Map<String, Object>> activities = (List<Map<String, Object>>) presence.get("activities");
        assertEquals(1, activities.size());
        assertTrue(activities.get(0).containsKey("name"));
    }
}
