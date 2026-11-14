package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.Test;

/** Verify shipped metadata, not a fictional selector or a device rendering claim. */
public class ShippedHookProfileTest {
    private static JSONObject profile(int version) throws Exception {
        String json = Files.readString(Path.of("src/main/assets/hook-config/" + version + ".json"));
        HookConfiguration.parse(json, version);
        return new JSONObject(json);
    }

    @Test
    public void inactiveFeaturePathsAndDiagnosticFieldsAreNotShipped() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            JSONObject root = profile(version);
            JSONObject hooks = root.getJSONObject("hooks");
            assertFalse(hooks.has("poll.bind"));
            assertFalse(hooks.has("embed.postPreviewBind"));
            assertFalse(hooks.has("embed.postPreviewRender"));
            assertFalse(root.has("constructorFallbackTypes"));
            JSONObject recycler = root.getJSONObject("fields").getJSONObject("RecyclerView");
            for (String key : new String[]{"stateP", "stateQ", "stateR", "stateS", "stateT", "stateU"}) {
                assertFalse(recycler.has(key));
            }
        }
    }

    @Test
    public void currentProfileHasOneExplicitIdentityAndConstructorRoute() throws Exception {
        JSONObject root = profile(352017);
        assertEquals("argument", root.getJSONObject("hooks").getJSONObject("message.render").getString("identitySource"));
        assertEquals("constructorProxy", root.getJSONObject("iconClick").getString("protocol"));
        assertEquals("c", root.getJSONObject("fields").getJSONObject("UnicodeEmojiContentNode").getString("getEmojiAnchor"));
        JSONObject constructors = root.getJSONObject("nodeConstructors");
        assertEquals(12, constructors.length());
        JSONObject heading = constructors.getJSONObject("HeadingContentNode");
        assertEquals("java.util.List", heading.getJSONArray("parameterTypes").getString(0));
        assertEquals("children", heading.getJSONArray("arguments").getJSONObject(0).getString("kind"));
        assertEquals(3, heading.getJSONArray("arguments").getJSONObject(1).getInt("value"));
        assertEquals("a", heading.getJSONArray("arguments").getJSONObject(2).getString("name"));
        assertFalse(root.getJSONObject("hooks").getJSONObject("message.bind").has("replyPreviewUsernameField"));
    }

    @Test
    public void olderProfileRetainsRichEmbedButNotUnverifiedClickOrReply() throws Exception {
        JSONObject root = profile(340013);
        assertEquals("boundView", root.getJSONObject("hooks").getJSONObject("message.render").getString("identitySource"));
        assertEquals(1, root.getJSONObject("hooks").getJSONObject("embed.bind").getInt("accessoryArgument"));
        assertFalse(root.getJSONObject("iconClick").getBoolean("enabled"));
        assertFalse(root.getJSONObject("hooks").getJSONObject("message.bind").has("referencedMessage"));
    }
}
