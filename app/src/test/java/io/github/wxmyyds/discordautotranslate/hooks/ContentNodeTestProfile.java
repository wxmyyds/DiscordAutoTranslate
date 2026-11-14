package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Explicit, test-only profile for the fake Discord content model classes. */
final class ContentNodeTestProfile {
    private static final Set<String> SEMANTIC_TYPES = new HashSet<>(Arrays.asList(
            "TextContentNode", "InlineCodeContentNode", "CodeBlockContentNode",
            "EmojiContentNode", "UnicodeEmojiContentNode", "CustomEmojiContentNode",
            "NewLineContentNode", "LineBreakContentNode", "ParagraphContentNode",
            "HeadingContentNode", "StrongContentNode", "EmphasisContentNode",
            "UserMentionContentNode", "UserOrRoleMentionContentNode",
            "StaticChannelMentionContentNode", "CommandMentionContentNode",
            "ChannelMentionContentNode", "ChannelNameContentNode",
            "FutureOpaqueContentNode", "TimestampContentNode", "LinkContentNode",
            "BulletListContentNode", "AnnotatedStructurableText",
            "PrimitiveStructurableText"));

    private ContentNodeTestProfile() {
    }

    static void activate(Class<?>... fixtureRoots) {
        try {
            JSONObject aliases = new JSONObject();
            for (Class<?> root : fixtureRoots) {
                addFixtureClasses(root, aliases);
            }
            JSONObject members = new JSONObject()
                    .put("getContent", "getContent")
                    .put("getItems", "getItems")
                    .put("getEmojiAnchor", "getContent")
                    .put("getAlt", "getAlt")
                    .put("getId", "getId");
            String json = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("versionCode", 352017)
                    .put("hooks", new JSONObject())
                    .put("types", new JSONObject())
                    .put("typeAliases", aliases)
                    .put("members", members)
                    .toString();
            HookConfiguration.activate(HookConfiguration.parse(json, 352017));
        } catch (JSONException e) {
            throw new AssertionError("Invalid content-node test profile", e);
        }
    }

    static void clear() {
        HookConfiguration.activate(null);
    }

    private static void addFixtureClasses(Class<?> type, JSONObject aliases) throws JSONException {
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (SEMANTIC_TYPES.contains(nested.getSimpleName())) {
                aliases.put(nested.getName(), nested.getSimpleName());
            }
            addFixtureClasses(nested, aliases);
        }
    }
}
