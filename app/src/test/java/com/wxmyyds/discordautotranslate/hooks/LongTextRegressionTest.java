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
 *       渲染层三级对齐回填（段落块 1:1 → 非空行 1:1 → 段落级：每段译文
 *       进本段首片段、段内其余置空），模型跨段落合并/增删内容时才整条
 *       保留原文——不做跨段启发式分配（那是乱序与内容丢失的来源）；</li>
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
            assertFalse("带标记文本不得包含 URL（标记文本已弃用，仅作库保留）：" + url,
                    r.segmented.contains(url));
        }
        // 纯文本（单链路请求正文）现在携带 URL 定位标记：模型必须原样保留它，
        // 回填按它切分对齐并删除回显，显示永远用原链接节点。
        assertTrue(r.text.contains(URLS[2]));
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
                translation.append("\n\n");
            }
            // 镜像请求文本结构：标题 + 换行 + URL 定位标记。
            translation.append("第").append(i + 1).append("块标题译文")
                    .append('\n').append(URLS[i % 3]);
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

    // --------------------------------------------- 重复广告消息（2026-10 三次上报）

    /** 7 组重复的「标题 + 加粗链接行 + TELEGRAM 标题」，URL 在加粗行内。 */
    private static List<Object> repeatedAdTree() {
        List<Object> nodes = new ArrayList<>();
        for (int g = 0; g < 7; g++) {
            nodes.add(new HeadingContentNode(
                    list(new TextContentNode("FREE GEMINI 4 ARGON")), 1));
            nodes.add(new TextContentNode("\n\n"));
            nodes.add(new StrongContentNode(list(
                    new TextContentNode(" LINK GEMINI 4 ARGON - "),
                    new LinkContentNode(list(new TextContentNode(
                            "https://clck.ru/3Wfv9G")), new Object()),
                    new TextContentNode(" "))));
            nodes.add(new TextContentNode("\n\n"));
            nodes.add(new HeadingContentNode(
                    list(new TextContentNode("TELEGRAM ONLY")), 1));
            if (g < 6) {
                nodes.add(new TextContentNode("\n\n"));
            }
        }
        return nodes;
    }

    @Test
    public void repeatedAdMessageTranslatesBlockByBlockWhenStructurePreserved() {
        // 保结构译文（每显示段落一块，共 21 块）→ 片段级 1:1：
        // 7 个标题、7 个加粗链接行、7 个 TELEGRAM 标题各拿自己的译文，
        // URL 链接节点逐字保留且位置不变。
        StringBuilder translation = new StringBuilder();
        for (int g = 0; g < 7; g++) {
            if (g > 0) {
                translation.append("\n\n");
            }
            translation.append("免费 GEMINI 4 ARGON")
                    .append("\n\n链接 GEMINI 4 ARGON - https://clck.ru/3Wfv9G")
                    .append("\n\n仅限 TELEGRAM");
        }
        List<Object> out = ContentNodes.replaceText(repeatedAdTree(), translation.toString());

        assertNotNull(out);
        int freeHeadings = 0;
        int onlyHeadings = 0;
        int boldLines = 0;
        int urlLinks = 0;
        for (Object n : out) {
            if (n instanceof HeadingContentNode) {
                String t = ((TextContentNode) ((HeadingContentNode) n).getContent().get(0))
                        .getContent();
                if (t.startsWith("免费")) {
                    assertEquals("免费 GEMINI 4 ARGON", t);
                    freeHeadings++;
                } else {
                    assertEquals("仅限 TELEGRAM", t);
                    onlyHeadings++;
                }
            } else if (n instanceof StrongContentNode) {
                boldLines++;
                StrongContentNode strong = (StrongContentNode) n;
                assertEquals("首尾空白属于原节点结构，译文落核心",
                        " 链接 GEMINI 4 ARGON - ",
                        ((TextContentNode) strong.getContent().get(0)).getContent());
                // URL 链接是加粗行的子节点，位置必须原样保留。
                for (Object child : strong.getContent()) {
                    if (child instanceof LinkContentNode) {
                        urlLinks++;
                        assertEquals("URL 必须逐字符保留",
                                "https://clck.ru/3Wfv9G",
                                ((TextContentNode) ((LinkContentNode) child).getContent().get(0))
                                        .getContent());
                    }
                }
            }
        }
        assertEquals(7, freeHeadings);
        assertEquals(7, onlyHeadings);
        assertEquals(7, boldLines);
        assertEquals(7, urlLinks);
    }

    @Test
    public void deduplicatedTranslationOfRepeatedBlocksKeepsOriginal() {
        // 模型对重复块去重（21 个段落只回 3 块）：无法确定哪块对应哪一组，
        // 拒绝回填、整条保留原文。提示词已明确要求逐个翻译重复段落，
        // 且请求不再携带 frequency_penalty（频率惩罚会促使模型跳过重复块）；
        // 若真机仍出现本形态，日志的 blocks/paragraphs 计数会直接显示。
        assertNull(ContentNodes.replaceText(repeatedAdTree(),
                "免费 GEMINI 4 ARGON\n\n链接 GEMINI 4 ARGON -\n\n仅限 TELEGRAM"));
    }

    // --------------------------------------------- 格式化文本与 URL 混排（2026-10 四次上报）

    // --------------------------------------------- 样式标记（2026-10 五次上报：斜体丢失）

    @Test
    public void styleMarkerPreservesItalicWhenModelKeepsTags() {
        // 上报形态：***text*** 与相邻普通文字同段，模型合并成一块返回——
        // 旧逻辑整块落进首片段、样式节点被清空（斜体丢失）。样式标记让
        // 模型在译文里原样保留 <xN>…</xN>，回填按标记把译文精确落回
        // 样式节点。
        List<Object> nodes = list(
                new TextContentNode("plain "),
                new com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode(
                        list(new TextContentNode("styled text"))),
                new TextContentNode(" tail"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "普通 <x1>斜体文字</x1> 尾巴");

        assertNotNull(out);
        assertEquals("普通 ", ((TextContentNode) out.get(0)).getContent());
        com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode em =
                (com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode) out.get(1);
        assertEquals("斜体文字", ((TextContentNode) em.getContent().get(0)).getContent());
        assertEquals(" 尾巴", ((TextContentNode) out.get(2)).getContent());
        assertEquals("原节点未被动过", "styled text",
                ((TextContentNode) ((com.wxmyyds.discordautotranslate.hooks
                        .ContentNodesTest.EmphasisContentNode) nodes.get(1))
                        .getContent().get(0)).getContent());
    }

    @Test
    public void styleMarkerMissingFallsBackToMergedBackfill() {
        // 模型没保留样式标记：自动回退到无标记对齐（合并处理）——
        // 译文保留、样式退化，不拒译。
        List<Object> nodes = list(
                new TextContentNode("plain "),
                new com.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode(
                        list(new TextContentNode("styled text"))),
                new TextContentNode(" tail"));

        List<Object> out = ContentNodes.replaceText(nodes, "普通 斜体 尾巴");

        assertNotNull("标记缺失必须回退而不是拒译", out);
        assertEquals("普通 斜体 尾巴", ((TextContentNode) out.get(0)).getContent());
        assertEquals("原节点未被动过", "styled text",
                ((TextContentNode) ((com.wxmyyds.discordautotranslate.hooks
                        .ContentNodesTest.EmphasisContentNode) nodes.get(1))
                        .getContent().get(0)).getContent());
    }

    @Test
    public void styleMarkerAndUrlMarkerWorkTogether() {
        // 加粗行内含 URL（广告消息形态）：样式标记与 URL 定位标记共存，
        // 两者都保留时样式与链接位置同时保真。
        List<Object> nodes = list(
                new TextContentNode("see "),
                new StrongContentNode(list(new TextContentNode("bold "))),
                new LinkContentNode(list(new TextContentNode(
                        "https://clck.ru/3Wfv9G")), new Object()),
                new TextContentNode(" now"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "见 <x1>加粗</x1> https://clck.ru/3Wfv9G 现在");

        assertNotNull(out);
        assertEquals("见 ", ((TextContentNode) out.get(0)).getContent());
        StrongContentNode strong = (StrongContentNode) out.get(1);
        assertEquals("加粗 ", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertSame("URL 链接节点原样保留", nodes.get(2), out.get(2));
        assertEquals(" 现在", ((TextContentNode) out.get(3)).getContent());
    }

    @Test
    public void urlMissingFromTranslationKeepsOriginal() {
        // URL 链接是请求里的定位标记，译文必须原样包含（提示词规则 6）。
        // 模型丢掉 URL = 定位失败，无法确定 URL 前后各自的译文边界 →
        // 拒绝回填、整条保留原文。
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold desc"))),
                new TextContentNode(" "),
                new LinkContentNode(list(new TextContentNode(
                        "https://clck.ru/3Wfv9G")), new Object()),
                new TextContentNode(" "),
                new TextContentNode("trailing text"));

        assertNull(ContentNodes.replaceText(nodes, "描述 尾巴"));
        // 原节点树原样保留：URL 逐字符不变，各文字片段未被动过。
        assertEquals("bold desc",
                ((TextContentNode) ((StrongContentNode) nodes.get(0)).getContent().get(0))
                        .getContent());
        assertEquals("https://clck.ru/3Wfv9G",
                ((TextContentNode) ((LinkContentNode) nodes.get(2)).getContent().get(0))
                        .getContent());
        assertEquals("trailing text", ((TextContentNode) nodes.get(4)).getContent());
    }

    @Test
    public void urlMarkerSplitsMergedTranslationAroundLinks() {
        // 上报形态（日志 targets=17 unsafeParagraphs=2 的最小复现）：URL 夹在
        // 同一段落的两个片段之间，模型把整段回成一行。URL 定位标记让回填
        // 能按 URL 把这一行切开——两段译文各归其位，URL 链接节点原样保留。
        LinkContentNode link = new LinkContentNode(list(new TextContentNode(
                "https://clck.ru/3Wfv9G")), new Object());
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold desc"))),
                new TextContentNode(" "),
                link,
                new TextContentNode(" "),
                new TextContentNode("trailing text"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "描述 https://clck.ru/3Wfv9G 尾巴");

        assertNotNull(out);
        StrongContentNode strong = (StrongContentNode) out.get(0);
        assertEquals("描述", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertSame("URL 链接节点原样保留", link, out.get(2));
        assertEquals("尾巴", ((TextContentNode) out.get(4)).getContent());
    }

    @Test
    public void urlSpanMergedSegmentsFillFirstAndBlankRest() {
        // URL 间隔内有多个相邻片段、模型合并成一块：间隔内段落级对齐——
        // 译文落进首片段、其余置空（与无 URL 的段落对齐同语义）。
        // 此前 copySpan 把空片段当「内容丢失」误杀，导致日志中
        // targets=17 urlMarks=12 的消息被整条拒绝（2026-10 实测）。
        List<Object> nodes = list(
                new TextContentNode("alpha"),
                new TextContentNode("beta"),
                new LinkContentNode(list(new TextContentNode(
                        "https://clck.ru/3Wfv9G")), new Object()),
                new TextContentNode("gamma"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "甲乙 https://clck.ru/3Wfv9G 丙");

        assertNotNull(out);
        assertEquals(4, out.size());
        assertEquals("甲乙", ((TextContentNode) out.get(0)).getContent());
        assertEquals("间隔内置空的片段原节点未被动过",
                "beta", ((TextContentNode) nodes.get(1)).getContent());
        assertSame("URL 链接节点原样保留", nodes.get(2), out.get(2));
        assertEquals("丙", ((TextContentNode) out.get(3)).getContent());
    }

    @Test
    public void urlEchoedTwiceKeepsOriginal() {
        // 模型把 URL 回显两份：回填后必然有一份以纯文本显示（实测故障形态），
        // 必须拒绝。
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold desc"))),
                new TextContentNode(" "),
                new LinkContentNode(list(new TextContentNode(
                        "https://clck.ru/3Wfv9G")), new Object()),
                new TextContentNode(" "),
                new TextContentNode("trailing text"));

        assertNull(ContentNodes.replaceText(nodes,
                "描述 https://clck.ru/3Wfv9G https://clck.ru/3Wfv9G 尾巴"));
    }

    @Test
    public void urlMovedBeforeItsTextKeepsOriginal() {
        // URL 被挪到译文开头：与树序不符，首个间隔无法承载它的片段 → 拒绝。
        List<Object> nodes = list(
                new StrongContentNode(list(new TextContentNode("bold desc"))),
                new TextContentNode(" "),
                new LinkContentNode(list(new TextContentNode(
                        "https://clck.ru/3Wfv9G")), new Object()),
                new TextContentNode(" "),
                new TextContentNode("trailing text"));

        assertNull(ContentNodes.replaceText(nodes,
                "https://clck.ru/3Wfv9G 描述 尾巴"));
    }

    @Test
    public void urlOwnParagraphDoesNotBlockParagraphAlignment() {
        // URL 独占一个段落（前后都是空行）：译文原样回带 URL，定位标记
        // 命中，前后段落各自 1:1 对齐，URL 原地保留。
        LinkContentNode link = new LinkContentNode(list(new TextContentNode(
                "https://clck.ru/3Wfv9G")), new Object());
        List<Object> nodes = list(
                new TextContentNode("para one"),
                new TextContentNode("\n\n"),
                link,
                new TextContentNode("\n\n"),
                new TextContentNode("para two"));

        List<Object> out = ContentNodes.replaceText(nodes,
                "段落一\n\nhttps://clck.ru/3Wfv9G\n\n段落二");

        assertNotNull(out);
        assertEquals("段落一", ((TextContentNode) out.get(0)).getContent());
        assertSame("URL 原地保留", link, out.get(2));
        assertEquals("https://clck.ru/3Wfv9G",
                ((TextContentNode) ((LinkContentNode) out.get(2)).getContent().get(0))
                        .getContent());
        assertEquals("段落二", ((TextContentNode) out.get(4)).getContent());
    }

    @Test
    public void repeatedBlocksWithUrlAfterBoldTranslateWithLinksInPlace() {
        // 多链接 + 重复段落：URL 在加粗行内部（行内只有一个可翻译片段），
        // 不构成「夹在两片段之间」——逐块正常翻译，链接各就各位。
        List<Object> nodes = new ArrayList<>();
        for (int g = 0; g < 2; g++) {
            nodes.add(new StrongContentNode(list(
                    new TextContentNode(" LINK GEMINI 4 ARGON - "),
                    new LinkContentNode(list(new TextContentNode(
                            "https://clck.ru/3Wfv9G")), new Object()),
                    new TextContentNode(" "))));
            if (g < 1) {
                nodes.add(new TextContentNode("\n\n"));
            }
        }
        StringBuilder translation = new StringBuilder();
        for (int g = 0; g < 2; g++) {
            if (g > 0) {
                translation.append("\n\n");
            }
            translation.append("链接 GEMINI 4 ARGON - https://clck.ru/3Wfv9G");
        }
        List<Object> out = ContentNodes.replaceText(nodes, translation.toString());

        assertNotNull(out);
        for (int g = 0; g < 2; g++) {
            StrongContentNode strong = (StrongContentNode) out.get(g * 2);
            // 首尾空格属于原节点结构，重建时从原节点取回。
            assertEquals(" 链接 GEMINI 4 ARGON - ",
                    ((TextContentNode) strong.getContent().get(0)).getContent());
            assertEquals("https://clck.ru/3Wfv9G",
                    ((TextContentNode) ((LinkContentNode) strong.getContent().get(1))
                            .getContent().get(0)).getContent());
        }
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
        // 实测故障：链接 + 单文本块的消息，URL 回显后出现两遍，第二遍是
        // 纯文本（不可点击）。现在的防重复机制：URL 进请求当定位标记，
        // 译文原样回带；回填按 URL 切分对齐并删除回显部分，显示永远只有
        // 原链接节点——回显不会成为额外文本。回显两份/丢失/改坏则拒绝。
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
        assertEquals("纯文本携带 URL 定位标记",
                " https://codex-relay.codes/register?aff=oqr0 \n "
                        + "https://api.yzmao.cn/sign-up?aff=IpHK \n"
                        + "free real gpt-6.1-sol, TRIAL WITHOUT REFS", r.text);

        // 模型按规则 6 原样回带两个 URL：回填按 URL 切分——URL 之间的
        // 间隔承载文本节点译文，回显的 URL 本身被删除，链接由原节点渲染，
        // 无重复行。
        List<Object> out = ContentNodes.replaceText(nodes,
                "https://codex-relay.codes/register?aff=oqr0\n"
                        + "https://api.yzmao.cn/sign-up?aff=IpHK\n"
                        + "免费真实 gpt-6.1-sol，无需推荐人即可试用");
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
        // 文本节点不含 URL：回显部分已按定位标记删除，不重复显示。
        String textNodeContent = ((TextContentNode) out.get(4)).getContent();
        assertFalse(textNodeContent.contains("codex-relay"));
        assertFalse(textNodeContent.contains("yzmao"));
    }
}
