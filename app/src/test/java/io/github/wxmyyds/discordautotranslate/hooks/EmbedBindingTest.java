package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fake native inputs only; structured fields, argument order and failure boundaries. */
public class EmbedBindingTest {
    private static final String ROW = "123456789012345678";

    public static class FieldModel {
        public Object c;
        public Object d;
        FieldModel(Object name, Object value) { c = name; d = value; }
    }

    public static class EmbedModel {
        public Object f;
        public Object j;
        public Object g;
        public Object brokenDescription() { throw new IllegalStateException(); }
        EmbedModel(Object title, Object description, Object fields) {
            f = title; j = description; g = fields;
        }
    }

    public static class Accessory {
        final String id;
        final LegacyModel embed;
        Accessory(String id, LegacyModel embed) { this.id = id; this.embed = embed; }
        public String getMessageId() { return id; }
        public LegacyModel getEmbed() { return embed; }
    }

    public static class LegacyModel {
        public Object getDescription() { return DESCRIPTION; }
        public Object getTitle() { return TITLE; }
        public List<Object> getFields() { return Collections.emptyList(); }
    }

    private static final Object TITLE = new Object();
    private static final Object DESCRIPTION = new Object();
    private static final Object NAME = new Object();
    private static final Object VALUE = new Object();
    private JSONObject root;

    @Before
    public void activateProfile() throws Exception {
        root = new JSONObject().put("schemaVersion", 1).put("versionCode", 352017)
                .put("hooks", new JSONObject())
                .put("types", new JSONObject().put("Embed", EmbedModel.class.getName())
                        .put("EmbedField", FieldModel.class.getName()))
                .put("fields", new JSONObject()
                        .put("Embed", new JSONObject().put("getTitle", "f")
                                .put("getDescription", "j").put("getFields", "g"))
                        .put("EmbedField", new JSONObject().put("getName", "c")
                                .put("getValue", "d")))
                .put("members", new JSONObject().put("getMessageId", "getMessageId")
                        .put("getEmbed", "getEmbed"));
        activate();
    }

    private void activate() throws Exception {
        HookConfiguration.activate(HookConfiguration.parse(root.toString(), 352017));
    }

    @After public void clearProfile() { HookConfiguration.activate(null); }

    private static JSONObject directSpec() throws Exception {
        return new JSONObject().put("inputSource", "arguments")
                .put("modelArgument", 1).put("messageIdArgument", 3);
    }

    private static EmbedBinding direct(Object embed, Object id) throws Exception {
        return EmbedBinding.read(Arrays.asList(null, embed, 10L, id), directSpec());
    }

    @Test
    public void directModelRetainsEveryStructuredObjectInPrimaryOrder() throws Exception {
        EmbedModel embed = new EmbedModel(TITLE, DESCRIPTION,
                Arrays.asList(new FieldModel(NAME, VALUE), null, new FieldModel(null, TITLE)));
        EmbedBinding binding = direct(embed, ROW);
        assertEquals(ROW, binding.messageId);
        assertEquals(Arrays.asList(DESCRIPTION, TITLE, NAME, VALUE, TITLE), binding.texts);
        assertSame(DESCRIPTION, binding.texts.get(0));
        assertSame(NAME, binding.texts.get(2));
        assertSame(VALUE, binding.texts.get(3));
        assertSame(DESCRIPTION, embed.j);
        assertSame(TITLE, embed.f);
    }

    @Test
    public void nullOptionalSegmentsAndImageOnlyCardAreValid() throws Exception {
        assertTrue(direct(new EmbedModel(null, null, null), ROW).texts.isEmpty());
        assertEquals(Collections.singletonList(VALUE), direct(new EmbedModel(null, null,
                Collections.singletonList(new FieldModel(null, VALUE))), ROW).texts);
    }

    @Test
    public void invalidIdentityAndMissingModelDoNotCreateBindings() throws Exception {
        EmbedModel embed = new EmbedModel(TITLE, DESCRIPTION, null);
        assertNull(direct(embed, null));
        assertNull(direct(embed, "not-a-message"));
        assertNull(direct(embed, 123L));
        assertNull(direct(null, ROW));
    }

    @Test
    public void oldAccessoryUsesExplicitGettersWithoutArgumentGuessing() throws Exception {
        root.getJSONObject("members").put("getDescription", "getDescription")
                .put("getTitle", "getTitle").put("getFields", "getFields");
        activate();
        JSONObject spec = new JSONObject().put("inputSource", "accessory")
                .put("accessoryArgument", 1);
        EmbedBinding binding = EmbedBinding.read(Arrays.asList(null,
                new Accessory(ROW, new LegacyModel())), spec);
        assertEquals(Arrays.asList(DESCRIPTION, TITLE), binding.texts);
        assertEquals(ROW, binding.messageId);
    }

    @Test
    public void malformedFieldsAndOutOfRangeArgumentsFailExplicitly() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> direct(new EmbedModel(TITLE, null, "invalid"), ROW));
        assertThrows(IllegalArgumentException.class, () -> EmbedBinding.read(
                Collections.emptyList(), directSpec()));
        assertThrows(IllegalArgumentException.class, () -> EmbedBinding.read(
                Collections.emptyList(), new JSONObject().put("inputSource", "guess")));
    }

    @Test
    public void fieldLimitIsCheckedBeforeRegistrationNotPartiallyTruncated() throws Exception {
        List<FieldModel> allowed = Collections.nCopies(25, new FieldModel(NAME, VALUE));
        assertEquals(50, direct(new EmbedModel(null, null, allowed), ROW).texts.size());
        assertThrows(IllegalArgumentException.class, () -> direct(
                new EmbedModel(TITLE, DESCRIPTION,
                        Collections.nCopies(26, new FieldModel(NAME, VALUE))), ROW));
    }

    @Test
    public void unavailableConfiguredFieldIsNotTreatedAsOptionalNull() throws Exception {
        root.getJSONObject("fields").getJSONObject("Embed").put("getDescription", "missing");
        activate();
        assertThrows(IllegalStateException.class,
                () -> direct(new EmbedModel(TITLE, DESCRIPTION, null), ROW));
    }

    @Test
    public void accessorFailureDoesNotSwitchToReadableField() throws Exception {
        root.put("accessors", new JSONObject().put("Embed",
                new JSONObject().put("getDescription", "brokenDescription")));
        activate();
        assertThrows(IllegalStateException.class,
                () -> direct(new EmbedModel(TITLE, DESCRIPTION, null), ROW));
    }
}
