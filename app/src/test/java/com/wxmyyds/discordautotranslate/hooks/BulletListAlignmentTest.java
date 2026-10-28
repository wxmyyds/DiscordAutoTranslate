package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 含列表项的消息回填回归（2026-10 上报：Codex Relay 广告消息保持英文）。
 *
 * <p>上报原文结构：标题段 / 引导句 / "What it offers:" / 5 项无序列表 /
 * 结尾段 / "Sign up here: " + 裸 URL，共 10 个可翻译片段。</p>
 *
 * <p>旧实现的两处缺陷（均可从源码核对，非对模型输出的猜测）：</p>
 * <ol>
 *   <li>{@code addSegment} 只在分隔符含 ≥2 个换行时推进段落组，而列表项
 *       之间的分隔是 {@code "\n• "}（单换行）。5 个列表项因此同属一个
 *       段落组，整条消息只有 6 个段落组（标题 / 引导句 / "What it
 *       offers:" / 整张列表 / 结尾段 / "Sign up here:"）。</li>
 *   <li>对齐层级只有「空行块 1:1 → 整行 1:1 → 段落级」。模型保留「每项
 *       一行」时，空行块数是 6（列表整体一块）而片段数是 10，整行数
 *       （含空行）也不等于 10——这两级都要求数量相等，对不上。此时若空行
 *       块数恰好等于段落组数（6）且 URL 锚点完好，段落级会把整块列表
 *       译文塞进首项、其余 4 项置空；块数不等于 6，或模型丢了/改坏了 URL
 *       锚点，则整条拒绝回填，界面保持英文（状态仍是 TRANSLATED）。上报
 *       时正文全部保持英文，说明当时不满足「6 块 + 锚点完好」这一组合；
 *       原始译文未留存，此处不宣称具体是哪一种。</li>
 * </ol>
 *
 * <p>修复约定：列表项各自成段落组；新增「非空行 1:1」层级（仅对含列表项
 * 的间隔生效，且要求非空行数 == 片段数）；列表片段剥离模型回显的项目
 * 符号（显示符号由原节点渲染）。不含列表的消息仍走原有判定，不启用该层级。</p>
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
    public void modelDroppingBlankLineBeforeListStillAlignsByNonBlankLines() {
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

        assertNotNull("缺少列表前的空行也必须能对齐", out);
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
    public void modelPuttingBlankLinesBetweenItemsStillTranslatesEveryBullet() {
        // 部分模型会把列表项之间也写成空行：空行块数恰好等于片段数，
        // 走「空行块 1:1」层级，同样需要逐项落位并剔除项目符号。
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (String b : ZH_BULLETS) {
            t.append("- ").append(b).append("\n\n");
        }
        t.append("你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentNodes.replaceText(reportedTree(), t.toString());

        assertNotNull("逐项空行也不能拒绝回填", out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            assertEquals(ZH_BULLETS[i],
                    ((TextContentNode) bullets.getItems().get(i).get(0)).getContent());
        }
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
