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
 *   <li>单链路：请求恒为无标记纯文本，引擎返回前做防损坏校验
 *       （残留标记/占位符/长度比例，见 {@code Segments.plainTextViolation}）；
 *       渲染层只在结构精确对应时回填（段落块 1:1 或非空行 1:1），
 *       结构不对应（模型合并/增删/重排段落）时整条保留原文，
 *       不做启发式分配（那是乱序与内容丢失的来源）；</li>
 *   <li>结构对齐时每个标题拿到自己的片段，空行结构来自原节点。</li>
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
        // 纯文本（无标记请求正文/检测/缓存键）同样不含 URL 链接显示文本：
        // 发给模型的任何文本都不该包含 URL（模型复述会造成重复/损坏）。
        assertFalse(r.text.contains(URLS[2]));
        assertTrue(r.text.contains("FREE CLAUDE CODE CRACK"));
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
    public void completelyMarkerlessSpamWithMismatchedStructureKeepsOriginal() {
        // 40 行单换行刷屏（原文 15 个标题片段）：段落块与行数都对不上，
        // 整条保留原文。防损坏由引擎负责——残留标记、占位符、长度异常
        // （疑似复读/丢失）的无标记译文在引擎一侧被拒（见 SegmentsTest）；
        // 带残缺标记的输出在这里同样保留原文。
        StringBuilder spam = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            spam.append("🔥🔥🔥 FREE CRACK 🔥🔥🔥\n");
        }
        List<Object> nodes = reportedMessageTree();
        assertNull(ContentNodes.replaceText(nodes, spam.toString()));
        HeadingContentNode first = (HeadingContentNode) nodes.get(0);
        assertEquals(TITLES[0], ((TextContentNode) first.getContent().get(0)).getContent());
        LinkContentNode third = (LinkContentNode) nodes.get(2);
        assertEquals(URLS[0],
                ((TextContentNode) third.getContent().get(0)).getContent());
    }

    @Test
    public void markerlessMergedStructureKeepsOriginal() {
        // 模型把 15 个标题合并成 8 块返回（结构不对应）：保留原文。
        // 旧实现按行启发式分配：空格行/空行顶掉一半标题节点（内容丢失）、
        // 剩余标题串位、多余行堆进最后一个节点（乱序+膨胀）。
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                merged.append("\n\n");
            }
            merged.append("合并段").append(i + 1);
        }
        List<Object> nodes = reportedMessageTree();
        assertNull(ContentNodes.replaceText(nodes, merged.toString()));
        HeadingContentNode first = (HeadingContentNode) nodes.get(0);
        assertEquals(TITLES[0], ((TextContentNode) first.getContent().get(0)).getContent());
    }

    // ---------------------------------------------------------------- 单链路无标记回填（2026-10 二次上报）

    @Test
    public void markerlessStructurePreservingTranslationMapsEveryBlockExactly() {
        // 单链路请求无标记纯文本；模型保结构返回时译文按空行分成 15 个
        // 段落块，与 15 个标题片段一一对应。旧实现按裸行切分：空行与
        // URL 链接留下的占位空格行也被当成片段，一半标题被置空、其余
        // 串位、最后节点堆叠多块译文（内容块重复、消息膨胀、URL 与
        // 正文错误关联）。
        List<Object> nodes = reportedMessageTree();
        StringBuilder translation = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                translation.append("\n \n\n"); // 镜像请求文本结构：URL 链接只贡献空格行
            }
            translation.append("第").append(i + 1).append("块标题译文");
        }
        List<Object> out = ContentNodes.replaceText(nodes, translation.toString());

        assertNotNull(out);
        assertEquals("节点树规模不变（结构全保留）", nodes.size(), out.size());
        int headingIndex = 0;
        int linkIndex = 0;
        for (Object n : out) {
            if (n instanceof HeadingContentNode) {
                headingIndex++;
                assertEquals("第" + headingIndex + " 个标题必须拿到自己的段落块",
                        "第" + headingIndex + "块标题译文",
                        ((TextContentNode) ((HeadingContentNode) n).getContent().get(0))
                                .getContent());
            } else if (n instanceof LinkContentNode) {
                assertEquals("URL 必须逐字符保留且仍与自己的标题相邻",
                        URLS[linkIndex % 3],
                        ((TextContentNode) ((LinkContentNode) n).getContent().get(0))
                                .getContent());
                linkIndex++;
            } else if (n instanceof TextContentNode) {
                assertEquals("组间空行节点原样保留", "\n\n",
                        ((TextContentNode) n).getContent());
            }
        }
        assertEquals(15, headingIndex);
        assertEquals(15, linkIndex);
    }

    @Test
    public void markerlessDuplicatedBlocksKeepOriginalEntirely() {
        // 模型复读：译文出现 20 个段落块（原文只有 15 个标题片段）→
        // 拒绝回填，整条保留原文。旧实现把多出的块全部堆进最后一个
        // 标题节点，表现为内容块异常重复、消息长度膨胀。
        StringBuilder duplicated = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                duplicated.append("\n\n");
            }
            duplicated.append("第").append(i + 1).append("块复读内容");
        }
        List<Object> nodes = reportedMessageTree();
        assertNull(ContentNodes.replaceText(nodes, duplicated.toString()));
        HeadingContentNode first = (HeadingContentNode) nodes.get(0);
        assertEquals(TITLES[0], ((TextContentNode) first.getContent().get(0)).getContent());
        LinkContentNode third = (LinkContentNode) nodes.get(2);
        assertEquals(URLS[0],
                ((TextContentNode) third.getContent().get(0)).getContent());
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

    // ---------------------------------------------------------------- URL 链接去重（2026-10 实测）

    @Test
    public void urlLinksAreNotDuplicatedInUnsegmentedRequests() {
        // 实测故障：链接 + 单文本块的消息（分段未启用），纯文本请求里仍带
        // URL 链接的显示文本 → 模型复述 URL → 整段回填后 URL 出现两遍，
        // 第二遍是纯文本（不可点击）。修复后纯文本与标记文本同口径排除
        // URL 链接，请求只含可翻译文本，模型无从复述。
        List<Object> nodes = list(
                new LinkContentNode(list(new TextContentNode(
                        "https://codex-relay.codes/register?aff=oqr0")), new Object()),
                new NewLineContentNode(),
                new LinkContentNode(list(new TextContentNode(
                        "https://api.yzmao.cn/sign-up?aff=IpHK")), new Object()),
                new NewLineContentNode(),
                new TextContentNode("free real gpt-6.1-sol, TRIAL WITHOUT REFS"));

        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(nodes));

        assertNotNull(r);
        // 单可翻译块：不启用分段标记，请求走纯文本路径。
        assertNull("单文本块不启用标记", r.segmented);
        assertEquals("纯文本不得包含 URL 链接显示文本",
                " \n \nfree real gpt-6.1-sol, TRIAL WITHOUT REFS", r.text);

        // 模型只回文本译文（请求里没有 URL 可复述）：
        // 回填后链接原样、文本节点承载译文，无重复行。
        List<Object> out = ContentNodes.replaceText(nodes,
                "免费真实 gpt-6.1-sol，无需推荐人即可试用");
        assertNotNull(out);
        assertEquals(5, out.size());
        assertSame(nodes.get(0), out.get(0));
        assertSame(nodes.get(2), out.get(2));
        assertEquals("https://codex-relay.codes/register?aff=oqr0",
                ((TextContentNode) ((LinkContentNode) out.get(0)).getContent().get(0))
                        .getContent());
        assertEquals("https://api.yzmao.cn/sign-up?aff=IpHK",
                ((TextContentNode) ((LinkContentNode) out.get(2)).getContent().get(0))
                        .getContent());
        assertEquals("免费真实 gpt-6.1-sol，无需推荐人即可试用",
                ((TextContentNode) out.get(4)).getContent());
        // 每条 URL 在输出里只出现一次（链接节点本身），文本节点不含 URL。
        String textNodeContent = ((TextContentNode) out.get(4)).getContent();
        assertFalse(textNodeContent.contains("codex-relay"));
        assertFalse(textNodeContent.contains("yzmao"));
    }
}
