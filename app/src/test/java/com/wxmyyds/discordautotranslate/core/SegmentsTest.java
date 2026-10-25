package com.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link Segments} 的分段标记协议：包标记、判定、解析、回退。
 *
 * <p>要点：解析必须做到「全有或全无」。模型漏段、重复段、编号越界、闭合标记缺失
 * 时一律返回 null，让上层回退到旧的行切分逻辑，绝不能把部分结果当成完整译文。</p>
 */
public class SegmentsTest {

    @Test
    public void wrapAddsMatchingOpenAndCloseTags() {
        assertEquals("<s1>hi</s1>", Segments.wrap(1, "hi"));
        assertEquals("<s12>你好</s12>", Segments.wrap(12, "你好"));
    }

    @Test
    public void isSegmentedDetectsProtocolTags() {
        assertTrue(Segments.isSegmented("<s1>hi</s1>"));
        assertTrue(Segments.isSegmented("prefix <s2>x</s2>"));
        assertFalse(Segments.isSegmented("plain text"));
        assertFalse(Segments.isSegmented(""));
        assertFalse(Segments.isSegmented(null));
    }

    @Test
    public void parseExtractsEverySegmentInOrder() {
        List<String> parts = Segments.parse("<s1>你好</s1>\n<s2>世界</s2>", 2);
        assertNotNull(parts);
        assertEquals(Arrays.asList("你好", "世界"), parts);
    }

    @Test
    public void parseReturnsNullWhenSegmentsAreOutOfOrder() {
        // 错序意味着模型重排/改写了段落，编号与原文位置无法对齐；
        // 按编号硬归位会掩盖错位本身，必须整体拒绝、保留原文。
        assertNull(Segments.parse("<s2>第二</s2><s1>第一</s1>", 2));
    }

    @Test
    public void parseKeepsInnerNewlinesAndSpaces() {
        List<String> parts = Segments.parse("<s1>第一行\n第二行</s1>", 1);
        assertNotNull(parts);
        assertEquals("第一行\n第二行", parts.get(0));
    }

    @Test
    public void parseToleratesWhitespaceInsideClosingTag() {
        List<String> parts = Segments.parse("<s1>你好</s1 >", 1);
        assertNotNull(parts);
        assertEquals("你好", parts.get(0));
    }

    @Test
    public void parseReturnsNullWhenASegmentIsMissing() {
        // 模型只译了一段：不能把剩余文本节点留空或错位。
        assertNull(Segments.parse("<s1>你好</s1>", 2));
    }

    @Test
    public void parseReturnsNullWhenASegmentIsRepeated() {
        // 重复编号会让后一段覆盖前一段，静默丢字；必须整体回退。
        assertNull(Segments.parse("<s1>甲</s1><s1>乙</s1>", 1));
        assertNull(Segments.parse("<s1>甲</s1><s1>乙</s1><s2>丙</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenTagIsRenumberedOutOfRange() {
        assertNull(Segments.parse("<s1>a</s1><s3>b</s3>", 2));
        assertNull(Segments.parse("<s0>a</s0><s1>b</s1>", 2));
    }

    @Test
    public void parseReturnsNullWhenClosingTagIsMissing() {
        assertNull(Segments.parse("<s1>你好", 1));
    }

    @Test
    public void parseReturnsNullForPlainText() {
        assertNull(Segments.parse("你好 你好", 2));
        assertNull(Segments.parse(null, 2));
        assertNull(Segments.parse("", 2));
    }

    @Test
    public void stripRemovesTagsAndKeepsContentInOrder() {
        assertEquals("你好\n世界", Segments.strip("<s1>你好</s1>\n<s2>世界</s2>"));
        // 只译了一段：剩下的标记被剥离，内容仍保留，不能把标签当文字显示。
        assertEquals("你好", Segments.strip("<s1>你好</s1>"));
    }

    @Test
    public void stripKeepsTextOutsideTags() {
        assertEquals("前 x", Segments.strip("前 <s1>x</s1>"));
        assertEquals("a你好b", Segments.strip("a<s1>你好</s1>b"));
    }

    @Test
    public void stripHandlesMissingCloseTagGracefully() {
        // 闭合标记缺失：只丢掉开标记，其后文字不得丢失。
        assertEquals("你好", Segments.strip("<s1>你好"));
    }

    @Test
    public void stripReturnsEmptyForNullOrEmpty() {
        assertEquals("", Segments.strip(null));
        assertEquals("", Segments.strip(""));
    }

    @Test
    public void parseReturnsNullWhenSegmentContentIsBlank() {
        // 被标记的段落在提取侧永不为空（空段落不占标记）；空段 = 模型丢内容。
        assertNull(Segments.parse("<s1></s1><s2>世界</s2>", 2));
        assertNull(Segments.parse("<s1>  </s1><s2>世界</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenStrayMarkerLeaksIntoContent() {
        // 实测：模型把改写后的编号（如 <s22>）夹在段内容里，回填后会作为
        // 字面量显示给用户。必须整体拒绝。
        assertNull(Segments.parse("<s1>你好 <s22>泄漏</s22> 些</s1>", 1));
        assertNull(Segments.parse("<s1>你好</s1><s2>世界 </s2>尾部</s2>", 2));
    }

    @Test
    public void parseReturnsNullWhenContentHasTemplatePlaceholder() {
        // 实测：小模型在长重复文本上幻觉出 SENSENOVA_..._XXXXXX 这类占位符。
        assertNull(Segments.parse(
                "<s1>SENSENOVA_6_8_FLASH_LITE_YYYMMDD_HHMMSS_XXXXXX</s1>", 1));
        assertNull(Segments.parse("<s1>占位 XXXXXX 符</s1>", 1));
        // 三个连续 X 不是占位符形态，不得误伤。
        List<String> ok = Segments.parse("<s1>版本 XXX 正常</s1>", 1);
        assertNotNull(ok);
        assertEquals("版本 XXX 正常", ok.get(0));
    }

    @Test
    public void parseReturnsNullWhenExtraTagAppearsAfterAllParts() {
        // 模型多输出了一段：额外的 <s22> 说明它自行改写了段落结构。
        assertNull(Segments.parse("<s1>a</s1><s2>b</s2><s22>多</s22>", 2));
    }

    @Test
    public void ruleMentionsTagsAndKeepsOutsideCharacters() {
        // 协议说明必须覆盖「不要合并/删除标记」与「保留标记外的字符」两条关键约束。
        assertTrue(Segments.RULE.contains("<sN>"));
        assertTrue(Segments.RULE.contains("Keep every"));
        assertTrue(Segments.RULE.contains("outside the tags"));
    }

    // ------------------------------------------------- 引擎降级重试（translateWithFallback）

    /** 记录每次发送文本的假请求回调。 */
    private static final class RecordingRoundTrip implements Segments.RoundTrip {
        final List<String> sent = new ArrayList<>();
        private final String[] responses;

        RecordingRoundTrip(String... responses) {
            this.responses = responses;
        }

        @Override
        public String roundTrip(String text) {
            sent.add(text);
            return responses[Math.min(sent.size() - 1, responses.length - 1)];
        }
    }

    /** 第 n 次调用抛异常的假请求回调。 */
    private static final class ThrowingRoundTrip implements Segments.RoundTrip {
        int calls;

        @Override
        public String roundTrip(String text) {
            calls++;
            if (calls == 1) {
                return "无标记首答";
            }
            throw new IllegalStateException("network down");
        }
    }

    @Test
    public void markCountCountsProtocolTags() {
        assertEquals(3, Segments.markCount("<s1>a</s1> <s2>b</s2>\n<s3>c</s3>"));
        assertEquals(1, Segments.markCount("x <s5>y</s5> z"));
        assertEquals(0, Segments.markCount("plain text"));
        assertEquals(0, Segments.markCount(null));
    }

    @Test
    public void tryParseReportsViolationReason() {
        Segments.Result ok = Segments.tryParse("<s1>a</s1><s2>b</s2>", 2);
        assertTrue(ok.ok());
        assertNull(ok.violation);
        assertEquals(Arrays.asList("a", "b"), ok.parts);

        Segments.Result missing = Segments.tryParse("<s1>a</s1>", 2);
        assertNull(missing.parts);
        assertNotNull("缺失段必须给出可定位的原因", missing.violation);
        assertTrue(missing.violation.contains("of 2"));

        Segments.Result order = Segments.tryParse("<s2>b</s2><s1>a</s1>", 2);
        assertNull(order.parts);
        assertNotNull(order.violation);
    }

    @Test
    public void unsegmentedRequestIsSingleRoundTrip() throws Exception {
        RecordingRoundTrip rt = new RecordingRoundTrip("你好");
        assertEquals("你好", Segments.translateWithFallback("hello world", rt));
        assertEquals(Collections.singletonList("hello world"), rt.sent);
    }

    @Test
    public void validSegmentedResponseIsReturnedWithoutRetry() throws Exception {
        String req = "<s1>hello</s1>\n<s2>world</s2>";
        RecordingRoundTrip rt = new RecordingRoundTrip("<s1>你好</s1><s2>世界</s2>");
        assertEquals("<s1>你好</s1><s2>世界</s2>",
                Segments.translateWithFallback(req, rt));
        assertEquals("协议完好时不得多发一次请求", 1, rt.sent.size());
    }

    @Test
    public void protocolViolationRetriesOnceWithPlainText() throws Exception {
        // 实测故障形态：模型丢失全部标记。必须用无标记纯文本重发一次。
        String req = "<s1>hello</s1>\n<s2>world</s2>";
        RecordingRoundTrip rt = new RecordingRoundTrip("你好世界");
        String out = Segments.translateWithFallback(req, rt);

        assertEquals("你好世界", out);
        assertEquals(2, rt.sent.size());
        assertEquals("重试必须发送无标记纯文本（strip 后的请求）",
                "hello\nworld", rt.sent.get(1));
    }

    @Test
    public void retryRoundTripFailurePropagates() {
        ThrowingRoundTrip rt = new ThrowingRoundTrip();
        try {
            Segments.translateWithFallback("<s1>a</s1><s2>b</s2>", rt);
            fail("重试的异常必须向上传播");
        } catch (Exception expected) {
            assertEquals("network down", expected.getMessage());
        }
    }

    @Test
    public void retryResponseWithStrayMarkerIsRejected() {
        RecordingRoundTrip rt = new RecordingRoundTrip("首答", "你好 <s3>漏</s3> 世界");
        try {
            Segments.translateWithFallback("<s1>a</s1><s2>b</s2>", rt);
            fail("重试译文残留标记必须拒绝");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }
    }

    @Test
    public void retryResponseWithPlaceholderIsRejected() {
        RecordingRoundTrip rt = new RecordingRoundTrip("首答",
                "SENSENOVA_6_8_FLASH_LITE_YYYMMDD_HHMMSS_XXXXXX");
        try {
            Segments.translateWithFallback("<s1>a</s1><s2>b</s2>", rt);
            fail("占位符必须拒绝");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }
    }

    @Test
    public void retryResponseLengthRatioViolationsAreRejected() {
        // 原文足够长（≥40）时比例校验生效：疑似丢失（过短）与疑似复读（过长）。
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            source.append("<s").append(i + 1).append(">paragraph number ")
                    .append(i).append(" with words</s").append(i + 1).append(">\n");
        }
        String req = source.toString();
        assertTrue(Segments.strip(req).length() >= 40);

        RecordingRoundTrip tooShort = new RecordingRoundTrip("x", "短");
        try {
            Segments.translateWithFallback(req, tooShort);
            fail("疑似内容丢失必须拒绝");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }

        StringBuilder spam = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            spam.append("复读写得越长越可疑，");
        }
        RecordingRoundTrip tooLong = new RecordingRoundTrip("x", spam.toString());
        try {
            Segments.translateWithFallback(req, tooLong);
            fail("疑似复读必须拒绝");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.PARSE, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }
    }

    @Test
    public void retryResponseOfReasonableLengthIsAccepted() throws Exception {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            source.append("<s").append(i + 1).append(">paragraph number ")
                    .append(i).append(" with words</s").append(i + 1).append(">\n");
        }
        StringBuilder zh = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            zh.append("第").append(i).append("段中文译文，长度正常。\n");
        }
        RecordingRoundTrip rt = new RecordingRoundTrip("x", zh.toString());
        assertEquals(zh.toString(), Segments.translateWithFallback(source.toString(), rt));
        assertEquals(2, rt.sent.size());
    }

    // ------------------------------------------------- 带标记请求超时的降级

    /** 第一次调用抛超时、其后返回给定应答的假请求回调。 */
    private static final class TimeoutFirstRoundTrip implements Segments.RoundTrip {
        final List<String> sent = new ArrayList<>();
        private final String later;

        TimeoutFirstRoundTrip(String later) {
            this.later = later;
        }

        @Override
        public String roundTrip(String text) throws Exception {
            sent.add(text);
            if (sent.size() == 1) {
                throw new TranslationException(TranslationException.Kind.TIMEOUT,
                        "请求超时（15000ms，Read timed out）");
            }
            return later;
        }
    }

    @Test
    public void markedRequestTimeoutFallsBackToPlainText() throws Exception {
        // 实测：同供应商纯文本长文很快、带标记长文稳定超时（非流式接口在
        // 生成完成前不回字节，标记+长内容让小模型生成失控）。
        // 超时后必须立即改发无标记请求，而不是原样重试注定超时的请求。
        TimeoutFirstRoundTrip rt = new TimeoutFirstRoundTrip("你好世界");
        String out = Segments.translateWithFallback(
                "<s1>hello</s1>\n<s2>world</s2>", rt);

        assertEquals("你好世界", out);
        assertEquals(2, rt.sent.size());
        assertEquals("重试必须发送无标记纯文本", "hello\nworld", rt.sent.get(1));
    }

    @Test
    public void markedRequestEmptyResponseFallsBackToPlainText() throws Exception {
        // 实测：推理型模型在带标记请求上思考 55s/196KB 帧却不出正文，
        // 提取结果为空；纯文本请求秒级返回正文。空译文同样立即降级。
        final List<String> sent = new ArrayList<>();
        Segments.RoundTrip rt = new Segments.RoundTrip() {
            @Override
            public String roundTrip(String text) throws Exception {
                sent.add(text);
                if (sent.size() == 1) {
                    throw new TranslationException(TranslationException.Kind.EMPTY,
                            "提取到的译文为空");
                }
                return "你好世界";
            }
        };
        String out = Segments.translateWithFallback(
                "<s1>hello</s1>\n<s2>world</s2>", rt);

        assertEquals("你好世界", out);
        assertEquals(2, sent.size());
        assertEquals("重试必须发送无标记纯文本", "hello\nworld", sent.get(1));
    }

    @Test
    public void plainRequestTimeoutPropagates() {
        // 无标记请求超时 = 真网络/供应商问题，不能无限降级。
        TimeoutFirstRoundTrip rt = new TimeoutFirstRoundTrip("不应到达");
        try {
            Segments.translateWithFallback("hello world", rt);
            fail("无标记请求超时必须原样上抛");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.TIMEOUT, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }
        assertEquals(1, rt.sent.size());
    }

    @Test
    public void markedRequestNonTimeoutErrorsDoNotTriggerPlainFallback() {
        // 非超时错误（认证失败、限流等）按原逻辑上抛，由调度器决定重试。
        Segments.RoundTrip rt = new Segments.RoundTrip() {
            int calls;

            @Override
            public String roundTrip(String text) throws Exception {
                calls++;
                throw new TranslationException(TranslationException.Kind.HTTP,
                        "认证失败（请检查 API 密钥）");
            }
        };
        try {
            Segments.translateWithFallback("<s1>a</s1><s2>b</s2>", rt);
            fail("非超时错误必须上抛");
        } catch (TranslationException expected) {
            assertEquals(TranslationException.Kind.HTTP, expected.kind());
        } catch (Exception other) {
            fail("unexpected: " + other);
        }
    }

    @Test
    public void plainTextViolationGuardsForSinglePath() {
        // 单链路引擎在返回译文前调用该校验：任何一项不过都判失败，
        // 由用户点图标重试，绝不把损坏内容送进节点树。
        assertNotNull("残留标记必须拦截",
                Segments.plainTextViolation("译文里残留</s2>字面量", "hello world"));
        assertNotNull("模板占位符必须拦截",
                Segments.plainTextViolation("SENSENOVA_6_8_FLASH_LITE_XXXXXX", "hello world"));
        assertNotNull("空译文必须拦截",
                Segments.plainTextViolation("  ", "hello world"));

        // 长度比例校验只对 ≥40 字符的原文生效。
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            source.append("paragraph ").append(i).append(' ');
        }
        String longSource = source.toString();
        assertTrue(longSource.length() >= 40);
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            flood.append(longSource); // 10 倍长度：复读刷屏
        }
        assertNotNull("复读（长度暴涨）必须拦截",
                Segments.plainTextViolation(flood.toString(), longSource));
        assertNotNull("内容大面积丢失必须拦截",
                Segments.plainTextViolation("丢了", longSource));

        assertNull("正常译文必须放行",
                Segments.plainTextViolation("你好世界", "hello world"));
        assertNull("短原文不做比例校验",
                Segments.plainTextViolation("很长很长的译文比原文还长好几倍", "hi"));
    }
}
