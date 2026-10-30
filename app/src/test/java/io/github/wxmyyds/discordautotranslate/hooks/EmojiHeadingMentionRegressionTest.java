package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.HeadingContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NewLineContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.CustomEmojiContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.OpaqueNodeTranslationTest.UserOrRoleMentionContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 2026-10 上报：角色提及 + 标题 + 两行「自定义 emoji + 文本」的消息翻译后仍英文。
 *
 * <p>上报原文：</p>
 * <pre>
 * &lt;@&amp;1555202011736313896&gt;
 * # Announcing 2 new abliterated models:
 * &lt;:qwen:1519013780426854520&gt; Qwen 3 27b
 * &lt;:deepseek:1520140362390438120&gt; Deepseek V4.1 Flash
 * </pre>
 *
 * <p>严格回填要求 URL、emoji 与样式标记均按请求结构原样保留；缺失或错位时，
 * 整条拒绝回填并保持原消息，不走忽略锚点或其它降级对齐。</p>
 */
public class EmojiHeadingMentionRegressionTest {

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }

    /** 上报消息的节点树（提取与回填共用的结构）。 */
    private static List<Object> reportedTree() {
        return list(
                new UserOrRoleMentionContentNode(list(new TextContentNode("Role Display Name"))),
                new NewLineContentNode(),
                new HeadingContentNode(list(new TextContentNode("Announcing 2 new abliterated models:")), 1),
                new NewLineContentNode(),
                new CustomEmojiContentNode(1519013780426854520L, ":qwen:"),
                new TextContentNode(" Qwen 3 27b"),
                new NewLineContentNode(),
                new CustomEmojiContentNode(1520140362390438120L, ":deepseek:"),
                new TextContentNode(" Deepseek V4.1 Flash"));
    }

    /** 提取：三个可翻译片段（标题 / 两行 emoji 后的文本），emoji 作为锚点进请求。 */
    @Test
    public void extractionCarriesHeadingEmojiAndTextSegments() {
        TextExtractor.Result r = TextExtractor.extract(
                new ContentNodesTest.AnnotatedStructurableText(reportedTree()));

        assertNotNull(r);
        assertEquals(3, r.segmentCount);
        assertTrue("标题文本必须进请求",
                r.text.contains("Announcing 2 new abliterated models:"));
        assertTrue("自定义 emoji alt 必须作为锚点进请求", r.text.contains(":qwen:"));
        assertTrue(r.text.contains(":deepseek:"));
        assertTrue(r.text.contains("Qwen 3 27b"));
        assertTrue(r.text.contains("Deepseek V4.1 Flash"));
        assertFalse("角色名不得进请求（提及是不可翻译结构）", r.text.contains("Role Display Name"));
    }

    /** 模型保留 emoji 锚点且逐行翻译时，消息正常回填。 */
    @Test
    public void modelKeepingEmojiAnchorsTranslatesEveryLine() {
        List<Object> nodes = reportedTree();
        List<Object> out = ContentNodes.replaceText(nodes,
                "宣布 2 款新的 abliterated 模型：\n"
                        + ":qwen: Qwen 3 27b\n"
                        + ":deepseek: Deepseek V4.1 Flash");

        assertNotNull("保留锚点时必须能回填", out);
        // mention 原样保留
        assertTrue(out.get(0) instanceof UserOrRoleMentionContentNode);
        // 标题拿到译文
        HeadingContentNode heading = (HeadingContentNode) out.get(2);
        assertEquals("宣布 2 款新的 abliterated 模型：",
                ((TextContentNode) heading.getContent().get(0)).getContent());
        // emoji 节点原样保留（同实例，位置来自节点树）
        assertSame(nodes.get(4), out.get(4));
        assertEquals(" Qwen 3 27b", ((TextContentNode) out.get(5)).getContent());
        assertSame(nodes.get(7), out.get(7));
        assertEquals(" Deepseek V4.1 Flash", ((TextContentNode) out.get(8)).getContent());
    }

    /**
     * 上报现象回归：模型丢弃自定义 emoji 锚点时必须拒绝回填，不按行猜测。
     */
    @Test
    public void modelDroppingEmojiAnchorsRejectsLinewiseTranslation() {
        List<Object> nodes = reportedTree();
        assertNull("emoji 锚点丢失时必须保持原消息",
                ContentNodes.replaceText(nodes,
                        "宣布 2 款新的 abliterated 模型：\nQwen 3 27b\nDeepseek V4.1 Flash"));
        assertEquals("Announcing 2 new abliterated models:",
                ((TextContentNode) ((HeadingContentNode) nodes.get(2))
                        .getContent().get(0)).getContent());
    }

    /**
     * 结构不匹配时整条保留原文。
     */
    @Test
    public void mergedTranslationWithDroppedAnchorsKeepsOriginal() {
        List<Object> nodes = reportedTree();
        List<Object> out = ContentNodes.replaceText(nodes,
                "宣布 2 款新模型：Qwen 3 27b 与 Deepseek V4.1 Flash");

        assertNull("段落/行结构对不上时必须保留原文", out);
        assertEquals("Announcing 2 new abliterated models:",
                ((TextContentNode) ((HeadingContentNode) nodes.get(2))
                        .getContent().get(0)).getContent());
    }
}