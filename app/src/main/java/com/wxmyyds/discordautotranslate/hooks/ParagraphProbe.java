package com.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.util.List;

/**
 * 【临时诊断代码，定位完成后必须删除】
 *
 * <p>排查「原文空行在译文里丢失」。</p>
 *
 * <h3>要区分的三种成因</h3>
 * <ol>
 *   <li><b>节点树形态</b>：空行到底由什么提供——独立的
 *       {@code NewLineContentNode} / {@code LineBreakContentNode}，
 *       还是 {@code ParagraphContentNode} 之间的分隔符？若某条消息的空行不是独立
 *       节点，那它本来就存放在文本节点内部，回填时会被模型合并。</li>
 *   <li><b>是否启用了标记协议</b>：标记只在文本节点数 ≥ 2 时启用。单文本节点的
 *       消息「你好\\n\\n@Pids」全部走回退路径，换行完全依赖模型自觉保留。</li>
 *   <li><b>译文自身的换行数</b>：模型返回的译文里是否还有连续换行，还是已被
 *       合并成空格。</li>
 * </ol>
 *
 * <h3>隐私</h3>
 * <p>正文一律不输出：文本节点只输出长度，字符串内容只输出「换行/空格」的计数量。
 * 仅当用户开启「详细日志」时输出（{@link XLog#d}）。</p>
 */
public final class ParagraphProbe {

    private static final int MAX_DEPTH = 12;

    private ParagraphProbe() {
    }

    /** 输出节点树的形状与每个文本节点的长度。 */
    static void logTree(String tag, @Nullable List<?> nodes) {
        try {
            StringBuilder sb = new StringBuilder();
            walk(tag, nodes, sb, 0);
            XLog.d("[para] " + tag + " tree=" + sb);
        } catch (Throwable t) {
            XLog.d("[para] tree probe failed: " + t.getClass().getSimpleName());
        }
    }

    private static void walk(String tag, @Nullable List<?> nodes, StringBuilder out, int depth) {
        if (nodes == null || depth > MAX_DEPTH) {
            return;
        }
        out.append('[');
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            Object n = nodes.get(i);
            if (n == null) {
                out.append("null");
                continue;
            }
            if (n instanceof List<?>) {
                walk(tag, (List<?>) n, out, depth + 1);
                continue;
            }
            String simple = n.getClass().getSimpleName();
            out.append(shortName(simple));
            // 文本节点只报长度，绝不输出内容。
            if ("TextContentNode".equals(simple)) {
                String s = Reflect.callString(n, "getContent");
                out.append('(').append(s == null ? "null" : s.length()).append(')');
                continue;
            }
            Object inner = Reflect.call(
                    Reflect.findMethod(n.getClass(), "getContent", 0), n);
            if (inner instanceof List<?>) {
                walk(tag, (List<?>) inner, out, depth + 1);
            }
        }
        out.append(']');
    }

    /**
     * 输出译文的结构统计：总长度、换行数、连续换行（空行）数、是否带分段标记。
     *
     * <p>只输出计数，不输出任何字符内容。</p>
     */
    static void logTranslation(String tag, @Nullable String translated) {
        try {
            if (translated == null) {
                XLog.d("[para] " + tag + " trans=null");
                return;
            }
            int newlines = 0;
            int blankLines = 0;
            int markers = 0;
            boolean segmented = false;
            for (int i = 0; i < translated.length(); i++) {
                char c = translated.charAt(i);
                if (c == '\n') {
                    newlines++;
                    // 连续两个换行之间没有任何非空白字符，即为空行。
                    if (i + 1 < translated.length() && translated.charAt(i + 1) == '\n') {
                        blankLines++;
                    }
                }
            }
            // 标记只数个数，不输出标记内容。
            markers = countMarkers(translated);
            segmented = markers > 0;
            XLog.d("[para] " + tag + " trans len=" + translated.length()
                    + " newlines=" + newlines
                    + " blankLines=" + blankLines
                    + " markers=" + markers
                    + " segmented=" + segmented);
        } catch (Throwable t) {
            XLog.d("[para] trans probe failed: " + t.getClass().getSimpleName());
        }
    }

    private static int countMarkers(String s) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = s.indexOf("<s", from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 2;
        }
    }

    /** 去掉包名与常见后缀，保持日志可读。 */
    private static String shortName(String simpleName) {
        return simpleName
                .replace("ContentNode", "")
                .replace("Content", "");
    }
}
