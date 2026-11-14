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

    public static final class GetterFixture {
        public Object getContent() {
            return "body";
        }

        public static void getContent$annotations() {
        }
    }

    public static final class MetadataOnlyFixture {
        public static void getContent$annotations() {
        }
    }

    public static final class SuffixedGetterFixture {
        public String getContentMetadata() {
            return "not-the-body";
        }
    }

    @Test
    public void exactGetterCannotSelectKotlinAnnotationsMethod() {
        Method getter = Reflect.findMethodExact(GetterFixture.class, "getContent", 0);
        assertNotNull(getter);
        assertEquals("getContent", getter.getName());
        assertEquals("body", Reflect.call(getter, new GetterFixture()));
        assertNull(Reflect.findMethodExact(MetadataOnlyFixture.class, "getContent", 0));
        assertNull(Reflect.findMethodExact(GetterFixture.class, "get", 0));
    }

    @Test
    public void stringGetterRequiresTheCompleteConfiguredName() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,\"hooks\":{},"
                + "\"types\":{\"Message\":\"" + SuffixedGetterFixture.class.getName()
                + "\"},\"accessors\":{\"Message\":{\"getContent\":\"getContent\"}}}";
        HookConfiguration.activate(HookConfiguration.parse(json, 352017));
        assertNull(Reflect.callString(new SuffixedGetterFixture(), "getContent"));
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
    public void readsConstructorIconClickConfigurationFromProfile() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,\"hooks\":{},"
                + "\"iconClick\":{\"enabled\":true,\"protocol\":\"constructorProxy\","
                + "\"spanClass\":\"lh.d\","
                + "\"requiredSuperclass\":\"android.text.style.ClickableSpan\","
                + "\"interfaces\":[\"zf.b\"],\"constructor\":{\"parameterTypes\":["
                + "\"java.lang.Integer\",\"java.lang.Integer\",\"java.lang.Integer\","
                + "\"java.lang.Boolean\",\"kotlin.jvm.functions.Function1\","
                + "\"kotlin.jvm.functions.Function1\",\"int\"],\"arguments\":["
                + "{\"kind\":\"null\"},{\"kind\":\"null\"},{\"kind\":\"null\"},"
                + "{\"kind\":\"null\"},{\"kind\":\"null\"},"
                + "{\"kind\":\"callback\",\"interface\":\"kotlin.jvm.functions.Function1\","
                + "\"method\":\"invoke\",\"action\":\"toggleTranslation\"},"
                + "{\"kind\":\"integer\",\"value\":255}]}}}";
        HookConfiguration config = HookConfiguration.parse(json, 352017);
        org.json.JSONObject iconClick = config.iconClickConfiguration();

        // Configuration parsing only; this does not instantiate a host span.
        assertNotNull(iconClick);
        assertTrue(iconClick.optBoolean("enabled"));
        assertEquals("constructorProxy", iconClick.optString("protocol"));
        assertEquals("lh.d", iconClick.getString("spanClass"));
        assertEquals("android.text.style.ClickableSpan",
                iconClick.getString("requiredSuperclass"));
        assertEquals("zf.b", iconClick.getJSONArray("interfaces").getString(0));
        org.json.JSONObject constructor = iconClick.getJSONObject("constructor");
        org.json.JSONArray parameterTypes = constructor.getJSONArray("parameterTypes");
        org.json.JSONArray arguments = constructor.getJSONArray("arguments");
        assertEquals(7, parameterTypes.length());
        assertEquals(7, arguments.length());
        for (int i = 0; i < 5; i++) {
            assertEquals("null", arguments.getJSONObject(i).getString("kind"));
        }
        assertEquals("kotlin.jvm.functions.Function1", parameterTypes.getString(5));
        org.json.JSONObject callback = arguments.getJSONObject(5);
        assertEquals("callback", callback.getString("kind"));
        assertEquals("kotlin.jvm.functions.Function1", callback.getString("interface"));
        assertEquals("invoke", callback.getString("method"));
        assertEquals("toggleTranslation", callback.getString("action"));
        assertEquals("int", parameterTypes.getString(6));
        assertEquals("integer", arguments.getJSONObject(6).getString("kind"));
        assertEquals(255, arguments.getJSONObject(6).getInt("value"));
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
