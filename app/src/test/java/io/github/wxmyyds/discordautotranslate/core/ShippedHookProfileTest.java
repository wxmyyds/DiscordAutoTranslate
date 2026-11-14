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
            assertEquals(1, root.getInt("schemaVersion"));
            JSONObject hooks = root.getJSONObject("hooks");
            JSONObject embed = hooks.getJSONObject("embed.bind");
            assertTrue(embed.getBoolean("enabled"));
            assertFalse(embed.has("reason"));
            JSONObject members = root.optJSONObject("members");
            for (String key : new String[]{"embedHolderViewField", "embedMessageIdGetter",
                    "embedGetter", "embedDescriptionGetter", "embedTitleGetter",
                    "embedFieldsGetter", "embedFieldNameGetter", "embedFieldValueGetter"}) {
                assertTrue(members == null || !members.has(key));
            }
            assertEquals(version == 352017, hooks.has("poll.bind"));
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
    public void currentEmbedUsesExactSmaliJavaSignatureAndStructuredFields() throws Exception {
        JSONObject root = profile(352017);
        JSONObject embed = root.getJSONObject("hooks").getJSONObject("embed.bind");
        JSONObject target = embed.getJSONObject("target");
        assertEquals("q9.q", target.getString("class"));
        assertEquals("b", target.getString("name"));
        assertFalse(target.getBoolean("static"));
        assertEquals("void", target.getString("returnType"));
        assertEquals(34, target.getInt("parameterCount"));
        assertFalse(target.has("namePrefix"));
        assertFalse(target.has("minParameterCount"));
        assertFalse(target.has("selection"));
        // J and D each occupy one Java argument, not two smali register slots.
        String[] expectedTypes = {
                "m9.f0", "n7.c", "long", "java.lang.String", "int", "int", "int",
                "boolean", "boolean", "boolean", "boolean", "boolean", "boolean",
                "ea.n", "ea.n", "cb.i0", "ag.c", "android.view.View$OnLongClickListener",
                "a50.r", "af.m", "af.m", "k9.g", "af.m", "ea.b", "ea.c",
                "af.m", "af.m", "af.m", "j8.c", "double",
                "boolean", "boolean", "boolean", "boolean"
        };
        JSONObject parameterTypes = target.getJSONObject("parameterTypes");
        assertEquals(34, expectedTypes.length);
        assertEquals(expectedTypes.length, parameterTypes.length());
        for (int i = 0; i < expectedTypes.length; i++) {
            assertEquals("Java parameter " + i, expectedTypes[i],
                    parameterTypes.getString(Integer.toString(i)));
        }
        assertEquals("arguments", embed.getString("inputSource"));
        assertEquals(1, embed.getInt("modelArgument"));
        assertEquals(3, embed.getInt("messageIdArgument"));
        assertFalse(embed.has("accessoryArgument"));
        JSONObject host = embed.getJSONObject("host");
        assertEquals("this", host.getString("source"));
        assertEquals(1, host.length());
        assertEquals(8, root.getJSONArray("requiredHooks").length());
        assertEquals("embed.bind", root.getJSONArray("requiredHooks").getString(3));
        JSONObject types = root.getJSONObject("types");
        assertEquals("n7.c", types.getString("Embed"));
        assertEquals("n7.o", types.getString("EmbedField"));
        JSONObject fields = root.getJSONObject("fields");
        JSONObject embedFields = fields.getJSONObject("Embed");
        assertEquals(3, embedFields.length());
        assertEquals("j", embedFields.getString("getDescription"));
        assertEquals("f", embedFields.getString("getTitle"));
        assertEquals("g", embedFields.getString("getFields"));
        JSONObject itemFields = fields.getJSONObject("EmbedField");
        assertEquals(2, itemFields.length());
        assertEquals("c", itemFields.getString("getName"));
        assertEquals("d", itemFields.getString("getValue"));
    }

    @Test
    public void pollUsesExactNativeScopesAndReceiversOnly() throws Exception {
        JSONObject root = profile(352017);
        String[] ids = {"poll.bind", "poll.answerBind", "poll.textSet", "poll.accessibilitySet"};
        String[] owners = {"ea.r0", "ba.h", "android.widget.TextView", "android.view.View"};
        String[] methods = {"v", "f", "setText", "setContentDescription"};
        String[][] parameters = {{"m9.g0", "ea.n", "ea.n", "k9.l"}, {"k3.k1", "int"},
                {"java.lang.CharSequence"}, {"java.lang.CharSequence"}};
        for (int i = 0; i < ids.length; i++) {
            JSONObject hook = root.getJSONObject("hooks").getJSONObject(ids[i]);
            JSONObject target = hook.getJSONObject("target");
            assertTrue(hook.getBoolean("enabled"));
            assertEquals(ids[i], root.getJSONArray("requiredHooks").getString(i + 4));
            assertEquals(owners[i], target.getString("class"));
            assertEquals(methods[i], target.getString("name"));
            assertFalse(target.getBoolean("static"));
            assertEquals("void", target.getString("returnType"));
            assertEquals(parameters[i].length, target.getInt("parameterCount"));
            assertEquals(parameters[i].length, target.getJSONObject("parameterTypes").length());
            for (int j = 0; j < parameters[i].length; j++) {
                assertEquals(parameters[i][j], target.getJSONObject("parameterTypes")
                        .getString(Integer.toString(j)));
            }
        }
        JSONObject question = root.getJSONObject("hooks").getJSONObject("poll.bind");
        assertEquals(0, question.getInt("accessoryArgument"));
        assertEquals("[\"g\",\"b\",\"a\"]", question.getJSONArray("textPath").toString());
        assertEquals("[\"e\"]", question.getJSONArray("messageIdPath").toString());
        assertEquals("[\"u\",\"P\",\"w\"]", question.getJSONArray("textViewPath").toString());
        JSONObject answer = root.getJSONObject("hooks").getJSONObject("poll.answerBind");
        assertEquals(0, answer.getInt("holderArgument"));
        assertEquals(1, answer.getInt("positionArgument"));
        assertEquals("[\"d\"]", answer.getJSONArray("itemsPath").toString());
        assertEquals("[\"c\",\"a\"]", answer.getJSONArray("answerIdPath").toString());
        assertEquals("[\"c\",\"b\",\"a\"]", answer.getJSONArray("textPath").toString());
        assertEquals("q8.f0", answer.getString("textBindingClass"));
        assertEquals("q8.d0", answer.getString("imageBindingClass"));
        assertEquals("[\"v\"]", answer.getJSONArray("textViewPath").toString());
        assertEquals("[\"i\"]", answer.getJSONArray("imageViewPath").toString());
        for (String id : ids) assertFalse(profile(340013).getJSONObject("hooks").has(id));
    }

    @Test
    public void malformedPollProfilesRejectBeforeInstallation() throws Exception {
        for (int caseIndex = 0; caseIndex < 4; caseIndex++) {
            JSONObject root = profile(352017);
            JSONObject hooks = root.getJSONObject("hooks");
            switch (caseIndex) {
                case 0: hooks.remove("poll.textSet"); break;
                case 1: hooks.getJSONObject("poll.bind").put("accessoryArgument", 4); break;
                case 2: hooks.getJSONObject("poll.answerBind").put("textPath", new org.json.JSONArray()); break;
                case 3: hooks.getJSONObject("poll.accessibilitySet").put("enabled", false); break;
            }
            assertThrows(org.json.JSONException.class,
                    () -> HookConfiguration.parse(root.toString(), 352017));
        }
    }

    @Test
    public void malformedEmbedInputsAreRejectedBeforeInstallation() throws Exception {
        for (int caseIndex = 0; caseIndex < 5; caseIndex++) {
            JSONObject root = profile(352017);
            JSONObject embed = root.getJSONObject("hooks").getJSONObject("embed.bind");
            switch (caseIndex) {
                case 0: embed.put("accessoryArgument", 0); break;
                case 1: embed.put("messageIdArgument", 34); break;
                case 2: embed.put("inputSource", "guess"); break;
                case 3: embed.getJSONObject("host").put("source", "ancestor"); break;
                case 4: embed.getJSONObject("target").put("static", true); break;
            }
            assertThrows(org.json.JSONException.class,
                    () -> HookConfiguration.parse(root.toString(), 352017));
        }
    }

    @Test
    public void olderProfileRetainsRichEmbedButNotUnverifiedClickOrReply() throws Exception {
        JSONObject root = profile(340013);
        assertEquals("boundView", root.getJSONObject("hooks").getJSONObject("message.render").getString("identitySource"));
        JSONObject embed = root.getJSONObject("hooks").getJSONObject("embed.bind");
        JSONObject target = embed.getJSONObject("target");
        assertEquals("com.discord.chat.presentation.message.viewholder.EmbedViewHolder",
                target.getString("class"));
        assertEquals("bind", target.getString("name"));
        assertFalse(target.getBoolean("static"));
        assertEquals(9, target.getInt("parameterCount"));
        assertEquals(1, target.getJSONObject("parameterTypes").length());
        assertEquals("com.discord.chat.presentation.message.messagepart.EmbedMessageAccessory",
                target.getJSONObject("parameterTypes").getString("1"));
        assertEquals("accessory", embed.getString("inputSource"));
        assertEquals(1, embed.getInt("accessoryArgument"));
        assertFalse(embed.has("modelArgument"));
        assertFalse(embed.has("messageIdArgument"));
        JSONObject host = embed.getJSONObject("host");
        assertEquals("field", host.getString("source"));
        assertEquals("itemView", host.getString("field"));
        assertEquals(2, host.length());
        assertEquals(3, root.getJSONArray("requiredHooks").length());
        for (int i = 0; i < root.getJSONArray("requiredHooks").length(); i++) {
            assertNotEquals("embed.bind", root.getJSONArray("requiredHooks").getString(i));
        }
        JSONObject members = root.getJSONObject("members");
        for (String getter : new String[]{"getMessageId", "getEmbed", "getDescription",
                "getTitle", "getFields", "getName", "getValue"}) {
            assertEquals(getter, members.getString(getter));
        }
        JSONObject types = root.getJSONObject("types");
        assertFalse(types.has("EmbedMessageAccessory"));
        assertFalse(types.has("Embed"));
        assertFalse(types.has("EmbedField"));
        assertFalse(root.getJSONObject("iconClick").getBoolean("enabled"));
        assertFalse(root.getJSONObject("hooks").getJSONObject("message.bind").has("referencedMessage"));
    }
}
