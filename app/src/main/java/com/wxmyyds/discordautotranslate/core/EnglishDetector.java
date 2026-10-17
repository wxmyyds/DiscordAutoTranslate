package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

/**
 * 英文（自然语言）识别。
 *
 * <p>目标：判断一条消息是否“值得翻译”。要求宁可漏翻也不要明显误翻。</p>
 *
 * <p>规则（第一版，可在测试中调整）：
 * <ol>
 *   <li>空内容、纯数字、纯符号、纯链接 → 不翻译。</li>
 *   <li>代码块占主体 → 不翻译。</li>
 *   <li>正文中 ASCII 字母占比达到阈值，且拉丁字母数量足够 → 判定为英文。</li>
 *   <li>含 CJK 字符比例较高 → 不翻译（已是中文或其他东亚语言）。</li>
 * </ol>
 */
public final class EnglishDetector {

    /** 判定结果与依据，便于诊断。 */
    public static final class Verdict {
        public final boolean shouldTranslate;
        public final String reason;

        Verdict(boolean shouldTranslate, String reason) {
            this.shouldTranslate = shouldTranslate;
            this.reason = reason;
        }
    }

    /** 有效拉丁字母的最小数量，低于此值不翻译（避免 "ok"、"lol" 之类）。 */
    private static final int MIN_LETTERS = 4;
    /** 拉丁字母占“非空白字符”的最低比例。 */
    private static final float MIN_LETTER_RATIO = 0.5f;
    /** CJK 字符占比超过该值时认为不是英文消息。 */
    private static final float MAX_CJK_RATIO = 0.2f;

    private EnglishDetector() {
    }

    public static Verdict evaluate(@Nullable String rawText, int textNodeCount,
                                   int opaqueNodeCount, boolean codeHeavy) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new Verdict(false, "empty");
        }
        if (codeHeavy) {
            return new Verdict(false, "code-block");
        }

        String text = rawText;

        // 去掉 URL，避免链接里的英文导致误判。
        text = text.replaceAll("https?://\\S+", " ");
        // 去掉行内代码内容。
        text = text.replaceAll("`[^`]*`", " ");
        // 去掉自定义表情与提及占位符。
        text = text.replaceAll(":[A-Za-z0-9_]+:", " ");
        text = text.replaceAll("@mention|#channel|\\[time\\]", " ");

        int letters = 0;
        int cjk = 0;
        int nonSpace = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            nonSpace++;
            if (isLatinLetter(c)) {
                letters++;
            } else if (isCjk(c)) {
                cjk++;
            }
        }

        if (nonSpace == 0) {
            return new Verdict(false, "no-visible-content");
        }
        float cjkRatio = (float) cjk / nonSpace;
        if (cjkRatio > MAX_CJK_RATIO) {
            return new Verdict(false, "cjk-dominant(" + round(cjkRatio) + ")");
        }
        if (letters < MIN_LETTERS) {
            return new Verdict(false, "too-few-letters(" + letters + ")");
        }
        float letterRatio = (float) letters / nonSpace;
        if (letterRatio < MIN_LETTER_RATIO) {
            return new Verdict(false, "low-letter-ratio(" + round(letterRatio) + ")");
        }
        if (textNodeCount == 0) {
            return new Verdict(false, "no-text-node");
        }
        if (opaqueNodeCount > textNodeCount * 3) {
            return new Verdict(false, "mostly-structure");
        }
        return new Verdict(true, "english(letters=" + letters + ",ratio=" + round(letterRatio) + ")");
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isCjk(char c) {
        // CJK 统一表意文字 + 扩展 A + 假名 + 韩文音节
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private static String round(float f) {
        return String.valueOf(Math.round(f * 100) / 100.0f);
    }
}
