package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * 无标记纯文本译文的防损坏校验。
 *
 * <p>引擎与渲染层共用 {@link #hasSegmentMarkers} 拒绝残留分段标记，
 * 不解析、剥离或修复标记。{@link #plainTextViolation} 还检查空文本、
 * 模板占位符与长度比例异常，不把损坏内容送进节点树。</p>
 */
public final class Segments {

    /** 段内夹带的标记碎片（开/闭均算）：协议被破坏，会把标记泄漏成字面量。 */
    private static final Pattern STRAY_MARKER = Pattern.compile("</?s\\s*\\d");

    /**
     * 模型幻觉出的模板占位符（如 {@code XXXXXX}）。
     *
     * <p>实测小模型在长重复文本上会输出 {@code SENSENOVA_..._HHMMSS_XXXXXX}
     * 这类占位符；正常译文不会出现 4 个以上连续大写 X。</p>
     */
    private static final Pattern PLACEHOLDER_RUN = Pattern.compile("X{4,}");

    private Segments() {
    }

    /** 检测开/闭分段标记及未闭合碎片；只拒绝，不修复。 */
    public static boolean hasSegmentMarkers(@Nullable String text) {
        return text != null && STRAY_MARKER.matcher(text).find();
    }

    /**
     * 无标记译文的防损坏校验：非空、无残留标记、无占位符、长度比例合理。
     *
     * <p>单链路（无标记纯文本请求）在引擎侧返回前调用：复读刷屏（长度
     * 暴涨）、内容大面积丢失（长度骤减）、标记泄漏与模板占位符都在这里
     * 拦下，判失败由用户手动重试。</p>
     *
     * <p>长度比例只对较长的原文（≥40 字符）生效：正常中英互译长度在
     * 0.25×～3× 之间，复读刷屏会明显超出，内容丢失会明显不足；
     * 过短的文本比例噪声太大，不做校验。</p>
     *
     * @return 违规描述；合格返回 null
     */
    @Nullable
    public static String plainTextViolation(@Nullable String translated, String source) {
        if (translated == null || translated.trim().isEmpty()) {
            return "译文为空";
        }
        if (hasSegmentMarkers(translated)) {
            return "译文中残留分段标记";
        }
        if (PLACEHOLDER_RUN.matcher(translated).find()) {
            return "译文含模板占位符";
        }
        int n = source.length();
        if (n >= 40) {
            int m = translated.length();
            if (m * 4 < n) {
                return "译文长度异常（" + m + "/" + n + "）：疑似内容丢失";
            }
            if (m > n * 3) {
                return "译文长度异常（" + m + "/" + n + "）：疑似复读";
            }
        }
        return null;
    }
}
