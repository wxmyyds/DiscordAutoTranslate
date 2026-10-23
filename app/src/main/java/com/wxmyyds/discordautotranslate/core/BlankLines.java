package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落（空行）切分：把一段文本按「空行」拆成若干块，并保留块之间的分隔符。
 *
 * <p><b>为什么需要它</b>：空行在 Discord 的节点树里并不总是独立节点。
 * 真机实测（节点树探针）可看到形如
 * {@code [Mention[Text] Text(2) Heading[Text] ...]} 的结构 —— 其中的
 * {@code Text(2)} 就是内容为 {@code "\n\n"} 的 {@code TextContentNode}；
 * 而一条纯文本消息 {@code "你好\n\n世界"} 也可能整体是一个 {@code TextContentNode}。</p>
 *
 * <p>若把这些换行当作可翻译正文发给模型，模型会把它们合并成空格或挪到串首，
 * 于是原文空行消失。本类把文本切成
 * {@code chunks[0] + seps[0] + chunks[1] + ...}，
 * 让调用方只翻译 {@code chunks}（且只翻译其中非空白的块），
 * 分隔符与块内的首尾空白按原样保留。</p>
 *
 * <p><b>切分规则</b>：分隔符是一段连续的空白字符（空格、制表、换行），
 * 且其中至少包含<b>两个</b>换行符。单个换行（普通换行）不算段落分隔，
 * 仍留在块内部，由模型按正常换行翻译。</p>
 *
 * <p>本类与 Android 框架无关，可在单元测试中直接验证。</p>
 */
public final class BlankLines {

    /** 切分结果：{@code chunks.size() == separators.size() + 1}。 */
    public static final class Split {
        /** 段落块，按顺序；可能包含空串（例如文本以空行开头）。 */
        public final List<String> chunks;
        /** 块之间的分隔符，数量比 {@link #chunks} 少一。 */
        public final List<String> separators;

        Split(List<String> chunks, List<String> separators) {
            this.chunks = chunks;
            this.separators = separators;
        }

        /** 原样重建（用于校验与测试）。 */
        public String join() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0) {
                    sb.append(separators.get(i - 1));
                }
                sb.append(chunks.get(i));
            }
            return sb.toString();
        }
    }

    private BlankLines() {
    }

    /** 文本是否只含空白（含空串与 null）。 */
    public static boolean isBlank(@Nullable String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按空行切分文本。返回结果一定满足 {@code join().equals(split(text).join())}。
     */
    public static Split split(@Nullable String text) {
        List<String> chunks = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            chunks.add(text == null ? "" : text);
            return new Split(chunks, separators);
        }
        int start = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (!Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            // 一段连续空白：数其中换行符个数，达到 2 个即视为段落分隔。
            int j = i;
            int newlines = 0;
            while (j < n && Character.isWhitespace(text.charAt(j))) {
                if (text.charAt(j) == '\n') {
                    newlines++;
                }
                j++;
            }
            if (newlines >= 2) {
                chunks.add(text.substring(start, i));
                separators.add(text.substring(i, j));
                start = j;
            }
            i = j;
        }
        chunks.add(text.substring(start));
        return new Split(chunks, separators);
    }
}
