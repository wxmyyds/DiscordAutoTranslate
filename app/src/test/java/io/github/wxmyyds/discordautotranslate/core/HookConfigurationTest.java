package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.json.JSONException;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;

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
    public void rejectsWrongVersionAndDisabledHook() throws Exception {
        String json = "{\"schemaVersion\":1,\"versionCode\":352017,"
                + "\"hooks\":{\"reply\":{\"enabled\":false}}}";
        HookConfiguration config = HookConfiguration.parse(json, 352017);

        assertNull(config.findMethod("reply", getClass().getClassLoader()));
        assertThrows(JSONException.class, () -> HookConfiguration.parse(json, 340013));
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
