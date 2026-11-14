package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure fake-host coverage of profile-selected constructors, without Discord or Android objects. */
public class ContentNodeConstructorTest {
    public static final class e5 {
        private final String a;

        public e5(String text) { a = text; }
        public String t() { return a; }
    }

    public static final class annotated {
        private final List<Object> a;

        public annotated(List<Object> children) { a = children; }
        public List<Object> nodes() { return a; }
    }

    /** Field order is level, children; constructor order is children, mask, level. */
    public static final class q0 {
        private final int a;
        private final List<Object> b;
        static int calls;
        static int copyCalls;
        static int lastMask;

        public q0(List<Object> children, int mask, int level) {
            calls++;
            lastMask = mask;
            if (mask != 3) throw new IllegalArgumentException("wrong heading mask");
            a = level;
            b = children;
        }

        public List<Object> body() { return b; }
        public q0 copy(List<Object> children, int level) {
            copyCalls++;
            return new q0(children, 3, level);
        }
    }

    /** Nullable field must be read as null, not confused with a missing field. */
    public static final class w0 {
        private final List<Object> c;
        private final String b;
        private final int a;
        static int calls;

        public w0(int mask, int level, String label, List<Object> children) {
            calls++;
            if (mask != 7) throw new IllegalArgumentException("wrong LHeading mask");
            c = children;
            b = label;
            a = level;
        }

        public List<Object> body() { return c; }
    }

    public static final class LinkData {
        final String destination;

        LinkData(String destination) { this.destination = destination; }
    }

    /** Nonstandard declaration order and host-class parameter exercise exact type resolution. */
    public static final class c1 {
        private final char j;
        private final LinkData d;
        private final double h;
        private final String c;
        private final boolean f;
        private final List<Object> a;
        private final Float i;
        private final long g;
        private final String b;
        private final int e;
        static int calls;

        public c1(int mask, List<Object> children, String destination, String title,
                  LinkData context, int flags, boolean trusted, long id, double weight,
                  Float score, char marker) {
            calls++;
            if (mask != 1023) throw new IllegalArgumentException("wrong link mask");
            a = children;
            b = destination;
            c = title;
            d = context;
            e = flags;
            f = trusted;
            g = id;
            h = weight;
            i = score;
            j = marker;
        }

        public List<Object> body() { return a; }
    }

    public static final class broken {
        private final List<Object> a;
        static int selectedCalls;
        static int otherCalls;
        static int copyCalls;

        public broken(int mask, List<Object> children) {
            otherCalls++;
            a = children;
        }

        public broken(List<Object> children, int mask) {
            selectedCalls++;
            throw new IllegalStateException("selected constructor fails");
        }

        public List<Object> body() { return a; }
        public broken copy(List<Object> children) {
            copyCalls++;
            return new broken(1, children);
        }
    }

    public static class inheritedFields {
        private final int level = 8;
    }

    public static final class inherited extends inheritedFields {
        private final List<Object> a;
        static int calls;
        static int copyCalls;

        public inherited(List<Object> children, int level) {
            calls++;
            a = children;
        }

        public List<Object> body() { return a; }
        public inherited copy(List<Object> children) {
            copyCalls++;
            return new inherited(children, 8);
        }
    }

    public static final class copyOnly {
        private final List<Object> a;
        private final int b;
        static int copyCalls;

        public copyOnly(List<Object> children, int level) { a = children; b = level; }
        public List<Object> body() { return a; }
        public List<Object> component1() { return a; }
        public int component2() { return b; }
        public copyOnly copy(List<Object> children, int level) {
            copyCalls++;
            return new copyOnly(children, level);
        }
    }

    @After
    public void clearProfile() { HookConfiguration.activate(null); }

    @Test
    public void rebuildsHeadingWithChildrenBeforeMaskAndLevelRegardlessOfFieldOrder()
            throws Exception {
        JSONObject root = profile();
        root.getJSONObject("nodeConstructors").put("HeadingContentNode", headingSpec());
        activate(root);
        e5 text = new e5("  Hello  ");
        List<Object> children = list(text);
        q0 original = new q0(children, 3, 4);
        List<Object> nodes = list(original);
        q0.calls = 0;
        q0.copyCalls = 0;

        List<Object> output = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(output);
        q0 rebuilt = (q0) output.get(0);
        assertNotSame(original, rebuilt);
        assertNotSame(children, rebuilt.b);
        assertEquals(4, rebuilt.a);
        assertEquals(3, q0.lastMask);
        assertEquals(1, q0.calls);
        assertEquals(0, q0.copyCalls);
        assertEquals("  你好  ", ((e5) rebuilt.b.get(0)).t());
        assertOriginal(nodes, original, children, text, "  Hello  ");
        assertEquals("  你好  ", extracted(output));
    }

    @Test
    public void preservesNullableLHeadingFieldAndExactIntegerMask() throws Exception {
        JSONObject root = profile();
        root.getJSONObject("nodeConstructors").put("LHeadingContentNode", spec(
                new String[]{"int", "int", "java.lang.String", "java.util.List"},
                integer(7), field("a"), field("b"), children()));
        activate(root);
        e5 text = new e5("Hello");
        List<Object> children = list(text);
        w0 original = new w0(7, 2, null, children);
        List<Object> nodes = list(original);
        w0.calls = 0;

        List<Object> output = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(output);
        w0 rebuilt = (w0) output.get(0);
        assertEquals(1, w0.calls);
        assertEquals(2, rebuilt.a);
        assertNull(rebuilt.b);
        assertEquals("你好", ((e5) rebuilt.c.get(0)).t());
        assertOriginal(nodes, original, children, text, "Hello");
        assertEquals("你好", extracted(output));
    }

    @Test
    public void preservesLinkDestinationNullableFieldsPrimitiveValuesAndHostContextIdentity()
            throws Exception {
        JSONObject root = profile();
        root.getJSONObject("nodeConstructors").put("LinkContentNode", spec(
                new String[]{"int", "java.util.List", "java.lang.String", "java.lang.String",
                        LinkData.class.getName(), "int", "boolean", "long", "double",
                        "java.lang.Float", "char"},
                integer(1023), children(), field("b"), field("c"), field("d"),
                field("e"), field("f"), field("g"), field("h"), field("i"), field("j")));
        activate(root);
        e5 text = new e5("Read documentation");
        List<Object> children = list(text);
        LinkData context = new LinkData("https://example.com/private?id=4");
        c1 original = new c1(1023, children, context.destination, null, context,
                41, true, 9000000001L, 0.625, null, '#');
        List<Object> nodes = list(original);
        c1.calls = 0;

        List<Object> output = ContentNodes.replaceText(nodes, "阅读文档");

        assertNotNull(output);
        c1 rebuilt = (c1) output.get(0);
        assertNotSame(original, rebuilt);
        assertEquals(1, c1.calls);
        assertSame(original.b, rebuilt.b);
        assertNull(rebuilt.c);
        assertSame(context, rebuilt.d);
        assertEquals(41, rebuilt.e);
        assertEquals(true, rebuilt.f);
        assertEquals(9000000001L, rebuilt.g);
        assertEquals(0.625, rebuilt.h, 0.0);
        assertNull(rebuilt.i);
        assertEquals('#', rebuilt.j);
        assertEquals("阅读文档", ((e5) rebuilt.a.get(0)).t());
        assertOriginal(nodes, original, children, text, "Read documentation");
        assertEquals("阅读文档", extracted(output));
        assertEquals(context.destination, original.d.destination);
    }

    @Test
    public void constructorInvocationFailureNeverTriesSuccessfulOverloadOrCopy() throws Exception {
        assertBrokenRejected(spec(new String[]{"java.util.List", "int"},
                children(), integer(1)), 1);
    }

    @Test
    public void failedContainerRejectsWholeTreeWithoutPublishingTranslatedSiblings() throws Exception {
        JSONObject root = profile();
        mapParagraph(root, broken.class);
        root.getJSONObject("nodeConstructors").put("ParagraphContentNode", spec(
                new String[]{"java.util.List", "int"}, children(), integer(1)));
        activate(root);
        e5 first = new e5("First\n\n");
        e5 second = new e5("Second");
        List<Object> children = list(second);
        broken original = new broken(1, children);
        List<Object> nodes = list(first, original);
        broken.selectedCalls = 0;
        broken.otherCalls = 0;
        broken.copyCalls = 0;

        assertNull(ContentNodes.replaceText(nodes, "第一\n\n第二"));

        assertEquals(1, broken.selectedCalls);
        assertEquals(0, broken.otherCalls);
        assertEquals(0, broken.copyCalls);
        assertSame(first, nodes.get(0));
        assertSame(original, nodes.get(1));
        assertSame(children, original.a);
        assertSame(second, children.get(0));
        assertEquals("First\n\n", first.t());
        assertEquals("Second", second.t());
        assertEquals("First\n\nSecond", extracted(nodes));
    }

    @Test
    public void missingExactConstructorNeverTriesDifferentSignatureOrCopy() throws Exception {
        assertBrokenRejected(spec(new String[]{"java.util.List", "java.lang.Integer"},
                children(), field("missing")), 0);
    }

    @Test
    public void unresolvedParameterClassNeverTriesAnotherConstructorOrCopy() throws Exception {
        assertBrokenRejected(spec(new String[]{"java.util.List", "fakehost.Missing"},
                children(), field("a")), 0);
    }

    @Test
    public void missingFieldFailsBeforeInvocationAndDoesNotUseCopy() throws Exception {
        assertBrokenRejected(spec(new String[]{"java.util.List", "int"},
                children(), field("absent")), 0);
    }

    @Test
    public void incompatibleFieldValueNeverTriesOtherOverloadOrCopy() throws Exception {
        assertBrokenRejected(spec(new String[]{"java.util.List", "int"},
                children(), field("a")), 0);
    }

    @Test
    public void fieldNameDoesNotGuessInheritedValues() throws Exception {
        JSONObject root = profile();
        mapParagraph(root, inherited.class);
        root.getJSONObject("nodeConstructors").put("ParagraphContentNode", spec(
                new String[]{"java.util.List", "int"}, children(), field("level")));
        activate(root);
        e5 text = new e5("Hello");
        List<Object> children = list(text);
        inherited original = new inherited(children, 8);
        List<Object> nodes = list(original);
        inherited.calls = 0;
        inherited.copyCalls = 0;

        assertNull(ContentNodes.replaceText(nodes, "你好"));

        assertEquals(0, inherited.calls);
        assertEquals(0, inherited.copyCalls);
        assertOriginal(nodes, original, children, text, "Hello");
    }

    @Test
    public void absentConstructorSpecSelectsExistingCopyStrategy() throws Exception {
        JSONObject root = profile();
        mapParagraph(root, copyOnly.class);
        HookConfiguration configuration = activate(root);
        assertNull(configuration.nodeConstructor("ParagraphContentNode"));
        e5 text = new e5("Hello");
        List<Object> children = list(text);
        copyOnly original = new copyOnly(children, 6);
        List<Object> nodes = list(original);
        copyOnly.copyCalls = 0;

        List<Object> output = ContentNodes.replaceText(nodes, "你好");

        assertNotNull(output);
        copyOnly rebuilt = (copyOnly) output.get(0);
        assertEquals(1, copyOnly.copyCalls);
        assertEquals(6, rebuilt.b);
        assertEquals("你好", ((e5) rebuilt.a.get(0)).t());
        assertOriginal(nodes, original, children, text, "Hello");
        assertEquals("你好", extracted(output));
    }

    @Test
    public void constructorTypeCanBeExplicitlyMappedByAliasOnly() throws Exception {
        JSONObject root = profile();
        root.put("types", new JSONObject());
        root.put("typeAliases", new JSONObject()
                .put(e5.class.getName(), "TextContentNode")
                .put(annotated.class.getName(), "AnnotatedStructurableText")
                .put(q0.class.getName(), "HeadingContentNode"));
        root.getJSONObject("nodeConstructors").put("HeadingContentNode", headingSpec());
        HookConfiguration configuration = activate(root);

        assertNotNull(configuration.nodeConstructor("HeadingContentNode"));
        q0 original = new q0(list(new e5("Hello")), 3, 2);
        List<Object> output = ContentNodes.replaceText(list(original), "你好");
        assertNotNull(output);
        assertEquals("Hello", extracted(list(original)));
        assertEquals("你好", extracted(output));
    }

    @Test
    public void malformedConstructorSectionAndUnmappedTypesFailParsing() throws Exception {
        JSONObject root = profile();
        root.put("nodeConstructors", new JSONArray());
        rejects(root);
        root.put("nodeConstructors", JSONObject.NULL);
        rejects(root);
        root.put("nodeConstructors", new JSONObject().put("HeadingContentNode", true));
        rejects(root);
        root.put("nodeConstructors", new JSONObject().put("UnmappedNode", headingSpec()));
        rejects(root);
    }

    @Test
    public void constructorArraysMustExistAndHaveEqualLength() throws Exception {
        rejectsSpec(new JSONObject().put("arguments", new JSONArray().put(children())));
        rejectsSpec(new JSONObject().put("parameterTypes", new JSONArray().put("java.util.List")));
        rejectsSpec(new JSONObject().put("parameterTypes", "java.util.List")
                .put("arguments", new JSONArray().put(children())));
        rejectsSpec(new JSONObject().put("parameterTypes", new JSONArray().put("java.util.List"))
                .put("arguments", "children"));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children()));
    }

    @Test
    public void constructorRequiresExactlyOneChildrenArgumentWithListType() throws Exception {
        rejectsSpec(spec(new String[]{"java.util.List"}, field("b")));
        rejectsSpec(spec(new String[]{"java.util.List", "java.util.List"},
                children(), children()));
        rejectsSpec(spec(new String[]{"java.lang.Object"}, children()));
    }

    @Test
    public void constructorRejectsUnknownKindsMissingFieldNamesAndNonExactTypes() throws Exception {
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(),
                new JSONObject().put("kind", "null")));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(),
                new JSONObject().put("kind", "field")));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(), field("")));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(), field("  ")));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(), field(" a")));
        rejectsSpec(spec(new String[]{"java.util.List", "int"}, children(),
                new JSONObject().put("kind", "field").put("name", 1)));
        rejectsSpec(spec(new String[]{"java.util.List", "*"}, children(), field("a")));
        rejectsSpec(spec(new String[]{"java.util.List", ""}, children(), field("a")));
        rejectsSpec(spec(new String[]{"java.util.List", " int"}, children(), field("a")));
        rejectsSpec(spec(new String[]{"java.util.List", "void"}, children(), field("a")));
        JSONObject nonStringType = headingSpec();
        nonStringType.getJSONArray("parameterTypes").put(1, 3);
        rejectsSpec(nonStringType);
        JSONObject nonObjectArgument = headingSpec();
        nonObjectArgument.getJSONArray("arguments").put(0, "children");
        rejectsSpec(nonObjectArgument);
        JSONObject nonStringKind = headingSpec();
        nonStringKind.getJSONArray("arguments").getJSONObject(0).put("kind", 1);
        rejectsSpec(nonStringKind);
    }

    @Test
    public void integerMetadataRequiresAnActualInRangeIntValue() throws Exception {
        Object[] invalidValues = {JSONObject.NULL, "3", 3.5, 2147483648L, -2147483649L};
        for (Object value : invalidValues) {
            JSONObject specification = headingSpec();
            specification.getJSONArray("arguments").getJSONObject(1).put("value", value);
            rejectsSpec(specification);
        }
        JSONObject missingValue = headingSpec();
        missingValue.getJSONArray("arguments").getJSONObject(1).remove("value");
        rejectsSpec(missingValue);
        rejectsSpec(spec(new String[]{"java.util.List", "long"}, children(), integer(3)));
    }

    private static void assertBrokenRejected(JSONObject specification, int expectedCalls)
            throws Exception {
        JSONObject root = profile();
        mapParagraph(root, broken.class);
        root.getJSONObject("nodeConstructors").put("ParagraphContentNode", specification);
        activate(root);
        e5 text = new e5("Hello");
        List<Object> children = list(text);
        broken original = new broken(1, children);
        List<Object> nodes = list(original);
        broken.selectedCalls = 0;
        broken.otherCalls = 0;
        broken.copyCalls = 0;

        assertNull(ContentNodes.replaceText(nodes, "你好"));

        assertEquals(expectedCalls, broken.selectedCalls);
        assertEquals(0, broken.otherCalls);
        assertEquals(0, broken.copyCalls);
        assertOriginal(nodes, original, children, text, "Hello");
    }

    private static void assertOriginal(List<Object> nodes, Object original,
                                       List<Object> children, e5 text, String source) {
        assertSame(original, nodes.get(0));
        assertSame(children, io.github.wxmyyds.discordautotranslate.core.Reflect
                .callMember(original, "getContent"));
        assertSame(text, children.get(0));
        assertEquals(source, text.t());
        assertEquals(source, extracted(nodes));
    }

    private static String extracted(List<Object> nodes) {
        TextExtractor.Result result = TextExtractor.extract(new annotated(nodes));
        assertNotNull(result);
        return result.text;
    }

    private static HookConfiguration activate(JSONObject root) throws JSONException {
        HookConfiguration configuration = HookConfiguration.parse(root.toString(), 352017);
        HookConfiguration.activate(configuration);
        return configuration;
    }

    private static JSONObject profile() throws JSONException {
        JSONObject types = new JSONObject()
                .put("TextContentNode", e5.class.getName())
                .put("AnnotatedStructurableText", annotated.class.getName())
                .put("HeadingContentNode", q0.class.getName())
                .put("LHeadingContentNode", w0.class.getName())
                .put("LinkContentNode", c1.class.getName());
        JSONObject accessors = new JSONObject()
                .put("TextContentNode", new JSONObject().put("getContent", "t"))
                .put("AnnotatedStructurableText", new JSONObject().put("getContent", "nodes"));
        for (String type : new String[]{"HeadingContentNode", "LHeadingContentNode",
                "LinkContentNode", "ParagraphContentNode"}) {
            accessors.put(type, new JSONObject().put("getContent", "body")
                    .put("copy", "copy").put("component1", "component1")
                    .put("component2", "component2"));
        }
        return new JSONObject().put("schemaVersion", 1).put("versionCode", 352017)
                .put("hooks", new JSONObject()).put("types", types)
                .put("accessors", accessors).put("nodeConstructors", new JSONObject());
    }

    private static void mapParagraph(JSONObject root, Class<?> hostClass) throws JSONException {
        root.getJSONObject("types").put("ParagraphContentNode", hostClass.getName());
    }

    private static JSONObject headingSpec() throws JSONException {
        return spec(new String[]{"java.util.List", "int", "int"},
                children(), integer(3), field("a"));
    }

    private static JSONObject spec(String[] types, JSONObject... arguments) throws JSONException {
        return new JSONObject().put("parameterTypes", new JSONArray(Arrays.asList(types)))
                .put("arguments", new JSONArray(Arrays.asList(arguments)));
    }

    private static JSONObject children() throws JSONException {
        return new JSONObject().put("kind", "children");
    }

    private static JSONObject field(String name) throws JSONException {
        return new JSONObject().put("kind", "field").put("name", name);
    }

    private static JSONObject integer(int value) throws JSONException {
        return new JSONObject().put("kind", "integer").put("value", value);
    }

    private static void rejectsSpec(JSONObject specification) throws JSONException {
        JSONObject root = profile();
        root.getJSONObject("nodeConstructors").put("HeadingContentNode", specification);
        rejects(root);
    }

    private static void rejects(JSONObject root) {
        assertThrows(JSONException.class, () -> HookConfiguration.parse(root.toString(), 352017));
    }

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }
}
