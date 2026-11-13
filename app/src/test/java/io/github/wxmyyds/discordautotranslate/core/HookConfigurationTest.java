package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;

public class HookConfigurationTest {
    public static final class SelectorFixture {
        public void render(String text, int flags) {
        }

        public static void render(SelectorFixture view, String text, int flags) {
        }
    }

    public static final class ObfuscatedFixture {
        private final String a = "from-field";

        public String d() {
            return "from-accessor";
        }
    }

    public static final class FieldOnlyFixture {
        private final String a = "from-field";
    }

    public static final class UnmappedAccessorFixture {
        public String getContent() {
            return "not-configured";
        }
    }

    @After
    public void clearActiveProfile() {
        HookConfiguration.activate(null);
    }

    @Test
    public void resolvesStaticHookByConfiguredSignature() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"requiredHooks\":[\"body\"],\"hooks\":{\"body\":{"
                + "\"target\":{\"class\":\"" + SelectorFixture.class.getName() + "\","
                + "\"name\":\"render\",\"static\":true,\"parameterCount\":3,"
                + "\"parameterTypes\":{\"0\":\"" + SelectorFixture.class.getName() + "\","
                + "\"1\":\"java.lang.String\",\"2\":\"int\"}}}}}";
        HookConfiguration config = HookConfiguration.parse(json, 352017);
        Method method = config.findMethod("body", getClass().getClassLoader());

        assertNotNull(method);
        assertTrue(config.hasAllRequiredTargets(getClass().getClassLoader()));
        assertTrue(config.hasAllRequiredHooks(Collections.singleton("body")));
        assertFalse(config.hasAllRequiredHooks(Collections.<String>emptySet()));
        assertEquals(3, method.getParameterCount());
        assertEquals(SelectorFixture.class, method.getParameterTypes()[0]);
    }

    @Test
    public void resolvesConfiguredAccessorAndFieldAliases() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{},\"types\":{\"TextContentNode\":\""
                + ObfuscatedFixture.class.getName() + "\",\"PrimitiveStructurableText\":\""
                + FieldOnlyFixture.class.getName() + "\"},\"accessors\":{"
                + "\"TextContentNode\":{\"getContent\":\"d\"}},\"fields\":{"
                + "\"PrimitiveStructurableText\":{\"getContent\":\"a\"}}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));

        assertEquals("from-accessor", Reflect.callString(new ObfuscatedFixture(), "getContent"));
        assertEquals("from-field", Reflect.callString(new FieldOnlyFixture(), "getContent"));
    }

    @Test
    public void configuredAccessorDoesNotFallThroughToField() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{},\"types\":{\"TextContentNode\":\""
                + FieldOnlyFixture.class.getName() + "\"},\"accessors\":{"
                + "\"TextContentNode\":{\"getContent\":\"missingGetter\"}},\"fields\":{"
                + "\"TextContentNode\":{\"getContent\":\"a\"}}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));

        assertNull(Reflect.callString(new FieldOnlyFixture(), "getContent"));
    }

    @Test
    public void unmappedRuntimeTypesAndMembersDoNotUseImplicitNames() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{},\"types\":{}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));

        assertNull(HookConfiguration.semanticTypeName(UnmappedAccessorFixture.class));
        assertNull(Reflect.callString(new UnmappedAccessorFixture(), "getContent"));
        assertNull(Reflect.findMethod(UnmappedAccessorFixture.class, "getContent", 0));
    }

    @Test
    public void profileTypeMappingsRequireExactClassNames() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":340013,"
                + "\"hooks\":{},\"types\":{\"TextContentNode\":\""
                + UnmappedAccessorFixture.class.getName() + "\"}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 340013));

        assertEquals("TextContentNode",
                HookConfiguration.semanticTypeName(UnmappedAccessorFixture.class));
        assertNull(HookConfiguration.semanticTypeName(FieldOnlyFixture.class));
    }

    @Test
    public void resolvesExplicitFullNameTypeAliases() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{},\"types\":{},\"typeAliases\":{\""
                + UnmappedAccessorFixture.class.getName()
                + "\":\"TextContentNode\"}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));

        assertEquals("TextContentNode",
                HookConfiguration.semanticTypeName(UnmappedAccessorFixture.class));
        assertNull(HookConfiguration.semanticTypeName(FieldOnlyFixture.class));
    }

    @Test
    public void rejectsWrongVersionAndDisabledHook() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"requiredHooks\":[\"reply\"],\"hooks\":{\"reply\":{\"enabled\":false}}}";
        HookConfiguration config = HookConfiguration.parse(json, 352017);

        assertNull(config.findMethod("reply", getClass().getClassLoader()));
        assertFalse(config.hasAllRequiredTargets(getClass().getClassLoader()));
        assertThrows(JSONException.class, () -> HookConfiguration.parse(json, 340013));
    }

    @Test
    public void readsIconClickProtocolFromProfileWithoutVersionBranch() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,\"hooks\":{},"
                + "\"iconClick\":{\"enabled\":true,\"protocol\":\"interfaceProxy\","
                + "\"interface\":\"example.Clickable\",\"methods\":{"
                + "\"onClick\":{\"action\":\"toggleTranslation\"},"
                + "\"getPriority\":{\"action\":\"returnEnum\","
                + "\"enumClass\":\"example.Priority\",\"enumValue\":\"HIGH\"}}}}";
        HookConfiguration config = HookConfiguration.parse(json, 352017);
        org.json.JSONObject iconClick = config.iconClickConfiguration();

        assertNotNull(iconClick);
        assertTrue(iconClick.optBoolean("enabled"));
        assertEquals("interfaceProxy", iconClick.optString("protocol"));
        assertEquals("toggleTranslation",
                iconClick.getJSONObject("methods").getJSONObject("onClick").optString("action"));
    }

    @Test
    public void readsExplicitlyDisabledIconClickProfile() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":340013,\"hooks\":{},"
                + "\"iconClick\":{\"enabled\":false,\"reason\":\"unverified\"}}";
        HookConfiguration config = HookConfiguration.parse(json, 340013);
        org.json.JSONObject iconClick = config.iconClickConfiguration();

        assertNotNull(iconClick);
        assertFalse(iconClick.optBoolean("enabled"));
        assertEquals("unverified", iconClick.optString("reason"));
    }

    @Test
    public void mapsObfuscatedRuntimeTypesFromProfile() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{},\"types\":{\"TextContentNode\":\""
                + SelectorFixture.class.getName() + "\"}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));

        assertEquals("TextContentNode",
                HookConfiguration.semanticTypeName(SelectorFixture.class));
    }
}
