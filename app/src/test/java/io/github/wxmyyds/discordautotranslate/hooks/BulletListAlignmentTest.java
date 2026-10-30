package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 列表项严格回填回归。
 *
 * <p>源树中的每个列表项独立成组。译文必须按相同顺序逐行给出；
 * 列表项之间新增空段落、合并或缺失译文时整条拒绝。请求中的 URL 与 emoji
 * 仍是必须保留的定位标记。</p>
 */
public class BulletListAlignmentTest {

    private static final String URL = "https://codex-relay.codes/register?aff=QlIz";

    private static final String[] BULLETS = {
            "Support for Codex CLI, Claude Code, and other AI coding tools",
            "Access to multiple AI models",
            "Pay-as-you-go pricing",
            "Custom API keys and spending limits",
            "R$5 in free credits after Telegram verification"
    };

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** 上报原文的节点树（与提取口径一致的 10 个可翻译片段）。 */
    private static List<Object> reportedTree() {
        List<List<Object>> items = new ArrayList<>();
        for (String bullet : BULLETS) {
            items.add(list(new TextContentNode(bullet)));
        }
        return list(
                new TextContentNode("Codex Relay — AI API for Coding"),
                new TextContentNode("\n\n"),
                new TextContentNode("If you're looking for an API provider for AI "
                        + "coding tools, check out Codex Relay."),
                new TextContentNode("\n\n"),
                new TextContentNode("What it offers:"),
                new TextContentNode("\n\n"),
                new BulletListContentNode(null, false, items),
                new TextContentNode("\n\n"),
                new TextContentNode("You can try it out with the free credits "
                        + "before spending anything."),
                new TextContentNode("\n\n"),
                new TextContentNode("Sign up here: "),
                new LinkContentNode(list(new TextContentNode(URL)), new Object()));
    }

    private static final String[] ZH_BULLETS = {
            "支持 Codex CLI、Claude Code 以及其他 AI 编程工具",
            "可使用多种 AI 模型",
            "按使用量计费",
            "自定义 API 密钥和消费限额",
            "通过 Telegram 验证后可获得 R$5 免费额度"
    };

    @Test
    public void reportedMessageHasTenSegments() {
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(reportedTree()));

        assertNotNull(r);
        assertEquals(10, r.segmentCount);
        assertTrue("列表符号进入请求正文", r.text.contains("• Support for Codex CLI"));
    }

    @Test
    public void modelKeepingBlankLinesAndDashMarkersTranslatesEveryBullet() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (String b : ZH_BULLETS) {
            t.append("- ").append(b).append('\n');
        }
        t.append("\n你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentNodes.replaceText(reportedTree(), t.toString());

        assertNotNull("结构完整的列表译文必须能回填", out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        assertEquals("5 个列表项都要拿到自己的译文", 5, bullets.getItems().size());
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            List<Object> item = bullets.getItems().get(i);
            assertEquals("第 " + (i + 1) + " 项译文",
                    ZH_BULLETS[i], ((TextContentNode) item.get(0)).getContent());
        }
        assertEquals("列表后的段落不得并进最后一项", 12, out.size());
        assertEquals("你可以在花任何钱之前先用免费额度试用。",
                ((TextContentNode) out.get(8)).getContent());
    }

    @Test
    public void modelEchoingBulletGlyphDoesNotDuplicateIt() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (String b : ZH_BULLETS) {
            t.append("• ").append(b).append('\n');
        }
        t.append("\n你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentNodes.replaceText(reportedTree(), t.toString());

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            String text = ((TextContentNode) bullets.getItems().get(i).get(0)).getContent();
            assertEquals("回显的符号必须剥离，显示由原节点负责", ZH_BULLETS[i], text);
            assertFalse(text.startsWith("•"));
        }
    }

    @Test
    public void modelDroppingListLeadBlankLineUsesKnownRowBoundaries() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n");
        for (String b : ZH_BULLETS) {
            t.append("- ").append(b).append('\n');
        }
        t.append("\n你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentNodes.replaceText(reportedTree(), t.toString());

        assertNotNull("源树列表项边界仍可按原行逐项映射", out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            assertEquals(ZH_BULLETS[i],
                    ((TextContentNode) bullets.getItems().get(i).get(0)).getContent());
        }
    }

    @Test
    public void listOnlyMessageAlignsEachItem() {
        List<List<Object>> items = new ArrayList<>();
        for (String bullet : BULLETS) {
            items.add(list(new TextContentNode(bullet)));
        }
        List<Object> nodes = list(new BulletListContentNode(null, false, items));

        StringBuilder t = new StringBuilder();
        for (String b : ZH_BULLETS) {
            t.append(b).append('\n');
        }
        List<Object> out = ContentNodes.replaceText(nodes, t.toString());

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(0);
        assertEquals(5, bullets.getItems().size());
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            assertEquals(ZH_BULLETS[i],
                    ((TextContentNode) bullets.getItems().get(i).get(0)).getContent());
        }
    }

    @Test
    public void mergedBulletParagraphKeepsOriginalInsteadOfClearingItems() {
        // 模型把 5 项合并成一行：无法确定每一项的对应关系，整条保留原文。
        // 旧实现按段落级对齐会把整块塞进首项、其余 4 项置空（列表塌成一行）。
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n")
                .append("- ").append(String.join("，", ZH_BULLETS)).append("\n\n")
                .append("你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> nodes = reportedTree();
        assertNull(ContentNodes.replaceText(nodes, t.toString()));

        BulletListContentNode bullets = (BulletListContentNode) nodes.get(6);
        assertEquals("原文列表项不得被清空", BULLETS[0],
                ((TextContentNode) bullets.getItems().get(0).get(0)).getContent());
        assertEquals(BULLETS[4],
                ((TextContentNode) bullets.getItems().get(4).get(0)).getContent());
    }

    @Test
    public void urlStaysIntactNextToTranslatedSignupLine() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (String b : ZH_BULLETS) {
            t.append("- ").append(b).append('\n');
        }
        t.append("\n你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> nodes = reportedTree();
        Object link = nodes.get(11);
        List<Object> out = ContentNodes.replaceText(nodes, t.toString());

        assertNotNull(out);
        assertSame("URL 链接节点必须原样保留", link, out.get(11));
        assertEquals(URL, ((TextContentNode) ((LinkContentNode) out.get(11))
                .getContent().get(0)).getContent());
        assertEquals("原节点尾随空白属于结构", "在此注册： ",
                ((TextContentNode) out.get(10)).getContent());
    }

    @Test
    public void listItemsSeparatedByBlankParagraphsAreRejected() {
        // 原结构要求列表项逐行对应。模型在项目之间另插空段落，结构不再一致。
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (String b : ZH_BULLETS) {
            t.append("- ").append(b).append("\n\n");
        }
        t.append("你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        assertNull("原始列表项之间是单换行，模型插入空段落后不再逐项对应",
                ContentNodes.replaceText(reportedTree(), t.toString()));
    }

    @Test
    public void orderedMarkersInListTranslationAreStripped() {
        // 列表项同样可能是有序列表：模型回显 "1. " 时也要剔除，
        // 否则显示成「• 1. 文本」。
        List<List<Object>> items = new ArrayList<>();
        items.add(list(new TextContentNode("first")));
        items.add(list(new TextContentNode("second")));
        List<Object> nodes = list(new BulletListContentNode(null, false, items));

        List<Object> out = ContentNodes.replaceText(nodes, "1. 第一\n2. 第二");

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(0);
        assertEquals("第一",
                ((TextContentNode) bullets.getItems().get(0).get(0)).getContent());
        assertEquals("第二",
                ((TextContentNode) bullets.getItems().get(1).get(0)).getContent());
    }

    @Test
    public void dashAtStartOfPlainParagraphIsNotTreatedAsListMarker() {
        // 不含列表节点的消息里，行首 "- " 是正文内容，不得剥离。
        List<Object> nodes = list(
                new TextContentNode("first paragraph"),
                new TextContentNode("\n\n"),
                new TextContentNode("second paragraph"));

        List<Object> out = ContentNodes.replaceText(nodes, "- 第一段\n\n第二段");

        assertNotNull(out);
        assertEquals("- 第一段", ((TextContentNode) out.get(0)).getContent());
        assertEquals("第二段", ((TextContentNode) out.get(2)).getContent());
    }

    @Test
    public void plainParagraphMessageStillRejectsMismatchedStructure() {
        // 不含列表的消息保持严格校验：多出来的行不能被塞进节点。
        List<Object> nodes = list(
                new TextContentNode("first paragraph"),
                new TextContentNode("\n\n"),
                new TextContentNode("second paragraph"));

        assertNull(ContentNodes.replaceText(nodes, "一\n二\n三\n四"));
    }
}
