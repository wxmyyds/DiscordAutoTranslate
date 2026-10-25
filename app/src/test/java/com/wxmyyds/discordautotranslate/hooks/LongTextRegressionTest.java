package com.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.AnnotatedStructurableText;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.HeadingContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.InlineCodeContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NewLineContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长重复文本翻译故障的复现回归（2026-10 用户上报）。
 *
 * <p>原文特征：5 组重复的「标题 + URL」块，共 15 个标题、15 个 URL。
 * 实测故障：小模型在 30 段重复内容上破坏分段协议，旧实现把损坏输出
 * （内容复读、尾部 🔥 刷屏、&lt;s22&gt; 泄漏、SENSENOVA_XXXXXX 占位符、
 * URL 字符损坏 IJYuv3wZ→lJYuv3wZ）切分后直接回填显示。</p>
 *
 * <p>修复后的契约：</p>
 * <ul>
 *   <li>URL 链接不进请求、不翻译，节点原样保留（逐字符一致）；</li>
 *   <li>带标记译文的校验是全有或全无：标记残缺/错序/夹带/占位符 → 整条保留原文；</li>
 *   <li>标记校验失败时引擎自动用无标记纯文本重发一次（重试译文经引擎
 *       防损坏校验），渲染层对无标记译文走整段回填；</li>
 *   <li>协议完好时每个标题拿到自己的片段，空行结构来自原节点。</li>
 * </ul>
 */
public class LongTextRegressionTest {

    private static final String[] TITLES = {
            "# FREE CODEX CRACK 🔥 2026 🔥 [REAL] [WORKING] [GPT 6 ASTRA] [UNLIMITED USAGE]",
            "# FREE ANTIGRAVITY CRACK 🔥 2026 🔥 [REAL] [WORKING] [GEMINI 4 ARGON] [UNLIMITED USAGE]",
            "# FREE CLAUDE CODE CRACK 🔥 2026 🔥 [REAL] [WORKING] [OPUS 5.5] [UNLIMITED USAGE]"
    };

    private static final String[] URLS = {
            "https://gofile.io/d/rfh2yimO",
            "https://gofile.io/d/kUa0UhnS",
            "https://gofile.io/d/IJYuv3wZ"
    };

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** 按上报原文构造节点树：5 组 × [标题, 换行, URL 链接]，组间空行节点。 */
    private static List<Object> reportedMessageTree() {
        List<Object> nodes = new ArrayList<>();
        for (int g = 0; g < 5; g++) {
            for (int i = 0; i < 3; i++) {
                nodes.add(new HeadingContentNode(list(new TextContentNode(TITLES[i])), 1));
                nodes.add(new NewLineContentNode());
                nodes.add(new LinkContentNode(list(new TextContentNode(URLS[i])), new Object()));
                if (!(g == 4 && i == 2)) {
                    nodes.add(new TextContentNode("\n\n")); // 组间空行
                }
            }
        }
        return nodes;
    }

    /** 构造 1..parts 的合规带标记译文，第 i 段内容为「第i段标题」。 */
    private static String wellFormedResponse(int parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= parts; i++) {
            if (i > 1) {
                sb.append('\n');
            }
            sb.append("<s").append(i).append(">第").append(i).append("段标题</s").append(i).append('>');
        }
        return sb.toString();
    }

    private static List<Integer> markerNumbers(String segmented) {
        Matcher m = Pattern.compile("<s(\\d+)>").matcher(segmented);
        List<Integer> nums = new ArrayList<>();
        while (m.find()) {
            nums.add(Integer.parseInt(m.group(1)));
        }
        return nums;
    }

    @Test
    public void segmentedRequestNumbersHeadingsOnlyAndExcludesUrls() {
        // 请求文本：15 个标题各占一个连续编号的标记；URL 一律不进请求。
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(reportedMessageTree()));

        assertNotNull(r);
        assertNotNull(r.segmented);
        List<Integer> nums = markerNumbers(r.segmented);
        assertEquals("15 个标题各占一个标记", 15, nums.size());
        for (int i = 0; i < 15; i++) {
            assertEquals("编号必须连续", Integer.valueOf(i + 1), nums.get(i));
        }
        for (String url : URLS) {
            assertFalse("URL 不得进入请求文本：" + url, r.segmented.contains(url));
        }
        // 纯文本（语言检测/缓存键）保持既有形态，仍包含 URL。
        assertTrue(r.text.contains(URLS[2]));
    }

    @Test
    public void wellFormedResponseTranslatesHeadingsAndKeepsUrlsVerbatim() {
        List<Object> nodes = reportedMessageTree();
        List<Object> out = ContentNodes.replaceText(nodes, wellFormedResponse(15));

        assertNotNull(out);
        assertEquals("节点树规模不变（结构全保留）", nodes.size(), out.size());
        int headingIndex = 0;
        int linkIndex = 0;
        for (int i = 0; i < out.size(); i++) {
            Object n = out.get(i);
            if (n instanceof HeadingContentNode) {
                headingIndex++;
                HeadingContentNode h = (HeadingContentNode) n;
                assertEquals("标题 level 必须保留", 1, h.component2());
                assertEquals("第" + headingIndex + " 个标题拿到自己的片段",
                        "第" + headingIndex + "段标题",
                        ((TextContentNode) h.getContent().get(0)).getContent());
            } else if (n instanceof LinkContentNode) {
                String url = ((TextContentNode) ((LinkContentNode) n).getContent().get(0))
                        .getContent();
                assertEquals("URL 必须逐字符保留", URLS[linkIndex % 3], url);
                assertSame("URL 链接节点必须原样复用", nodes.get(i), n);
                linkIndex++;
            } else if (n instanceof TextContentNode) {
                assertEquals("组间空行节点原样保留", "\n\n",
                        ((TextContentNode) n).getContent());
            } else if (n instanceof NewLineContentNode) {
                // 换行节点保持类型即可
            } else {
                throw new AssertionError("unexpected node: " + n);
            }
        }
        assertEquals(15, headingIndex);
        assertEquals(15, linkIndex);
    }

    @Test
    public void repeatedIdenticalSourcesStillGetSequentialMarkers() {
        // 全部 15 个标题完全相同（原文本就是高度重复）：标记仍然 1..15，
        // 回填按位置对齐，不会因为内容重复而串位。
        List<Object> nodes = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            nodes.add(new TextContentNode(TITLES[0]));
            nodes.add(new NewLineContentNode());
            nodes.add(new LinkContentNode(list(new TextContentNode(URLS[0])), new Object()));
            if (i < 14) {
                nodes.add(new TextContentNode("\n\n"));
            }
        }
        TextExtractor.Result r = TextExtractor.extract(new AnnotatedStructurableText(nodes));
        assertNotNull(r);
        List<Integer> nums = markerNumbers(r.segmented);
        assertEquals(15, nums.size());
        for (int i = 0; i < 15; i++) {
            assertEquals(Integer.valueOf(i + 1), nums.get(i));
        }

        List<Object> out = ContentNodes.replaceText(nodes, wellFormedResponse(15));
        assertNotNull(out);
        assertEquals("第7段标题",
                ((TextContentNode) out.get(6 * 4)).getContent());
        assertEquals(URLS[0],
                ((TextContentNode) ((LinkContentNode) out.get(6 * 4 + 2)).getContent().get(0))
                        .getContent());
    }

    // ---------------------------------------------------------------- 损坏输出必须整条回退

    private static void assertKeepsOriginal(String corrupted) {
        List<Object> nodes = reportedMessageTree();
        assertNull(ContentNodes.replaceText(nodes, corrupted));
        // 原节点树未被改动：标题与 URL 全部原样。
        HeadingContentNode first = (HeadingContentNode) nodes.get(0);
        assertEquals(TITLES[0], ((TextContentNode) first.getContent().get(0)).getContent());
        LinkContentNode third = (LinkContentNode) nodes.get(2);
        assertEquals(URLS[0],
                ((TextContentNode) third.getContent().get(0)).getContent());
    }

    @Test
    public void truncatedTailMissingLaterMarkersKeepsOriginal() {
        // 模型只输出前 9 段：后半内容丢失，必须整条回退。
        assertKeepsOriginal(wellFormedResponse(9));
    }

    @Test
    public void duplicatedGroupRenumberedKeepsOriginal() {
        // 模型复读前 3 段并改写编号（实测形态）。
        assertKeepsOriginal(
                "<s1>第1段标题</s1>\n<s2>第2段标题</s2>\n<s3>第3段标题</s3>\n"
                        + "<s4>第1段标题</s4>\n<s5>第2段标题</s5>");
    }

    @Test
    public void strayMarkerInsideContentKeepsOriginal() {
        // <s22> 泄漏进段内容：回填后会作为字面量显示给用户。
        assertKeepsOriginal(wellFormedResponse(15).replaceFirst("</s1>", " <s22>泄漏</s22></s1>"));
    }

    @Test
    public void hallucinatedPlaceholderKeepsOriginal() {
        // SENSENOVA_..._XXXXXX 模板占位符：模型幻觉，不是译文。
        assertKeepsOriginal(wellFormedResponse(15).replaceFirst("第5段标题",
                "SENSENOVA_6_8_FLASH_LITE_YYYMMDD_HHMMSS_XXXXXX"));
    }

    @Test
    public void outOfOrderMarkersKeepsOriginal() {
        // 错序：编号与原文位置无法对齐。
        assertKeepsOriginal(
                "<s1>第1段标题</s1>\n<s3>第3段标题</s3>\n<s2>第2段标题</s2>\n"
                        + wellFormedResponse(15).substring(wellFormedResponse(15)
                        .indexOf("<s4>")));
    }

    @Test
    public void emptySegmentKeepsOriginal() {
        // 空段 = 模型丢弃了该段内容。
        assertKeepsOriginal(wellFormedResponse(15).replaceFirst("第7段标题", "  "));
    }

    @Test
    public void completelyMarkerlessOutputFallsBackToWholeTextBackfill() {
        // 完全无标记的输出（模型丢光标记，或引擎无标记重试的产物）：
        // 渲染层不再拒绝，走整段回填。防损坏由引擎负责——残留标记、占位符、
        // 长度异常（疑似复读/丢失）的无标记译文在 Segments.translateWithFallback
        // 一侧被拒（见 SegmentsTest）；带残缺标记的输出在这里仍然保留原文。
        StringBuilder spam = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            spam.append("🔥🔥🔥 FREE CRACK 🔥🔥🔥\n");
        }
        List<Object> out = ContentNodes.replaceText(reportedMessageTree(), spam.toString());

        assertNotNull("无标记译文必须回填（否则消息永远显示原文）", out);
        // 首个标题节点承载第一行，URL 链接不受影响。
        HeadingContentNode first = (HeadingContentNode) out.get(0);
        assertEquals("🔥🔥🔥 FREE CRACK 🔥🔥🔥",
                ((TextContentNode) first.getContent().get(0)).getContent());
        LinkContentNode link = (LinkContentNode) out.get(2);
        assertEquals(URLS[0],
                ((TextContentNode) link.getContent().get(0)).getContent());
    }

    // ---------------------------------------------------------------- 混合格式的长文本

    @Test
    public void formattedLongTextWithCodeAndUrlsKeepsStructure() {
        // 加粗 + 行内代码 + URL 链接混排：代码节点与 URL 原样，文本逐段回填。
        InlineCodeContentNode code = new InlineCodeContentNode("npm install");
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold title"))),
                new TextContentNode("\n\n"),
                new TextContentNode("run"),
                code,
                new TextContentNode("now"),
                new TextContentNode("\n"),
                new LinkContentNode(list(new TextContentNode(URLS[1])), new Object()));
        List<Object> out = ContentNodes.replaceText(nodes,
                "<s1>加粗标题</s1>\n\n<s2>运行</s2> <s3>现在</s3>");

        assertNotNull(out);
        StrongContentNode strong = (StrongContentNode) out.get(0);
        assertEquals("加粗标题", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertEquals("\n\n", ((TextContentNode) out.get(1)).getContent());
        assertEquals("运行", ((TextContentNode) out.get(2)).getContent());
        assertSame("行内代码原样保留", code, out.get(3));
        assertEquals("现在", ((TextContentNode) out.get(4)).getContent());
        assertSame("URL 链接原样保留", nodes.get(6), out.get(6));
        assertEquals(URLS[1],
                ((TextContentNode) ((LinkContentNode) out.get(6)).getContent().get(0))
                        .getContent());
    }

    @Test
    public void shortSingleSegmentMessageStillTranslates() {
        // 短文本（单段）不受新校验影响，仍整段回填。
        List<Object> out = ContentNodes.replaceText(
                list(new TextContentNode("hello")), "你好");

        assertNotNull(out);
        assertEquals("你好", ((TextContentNode) out.get(0)).getContent());
    }
}
