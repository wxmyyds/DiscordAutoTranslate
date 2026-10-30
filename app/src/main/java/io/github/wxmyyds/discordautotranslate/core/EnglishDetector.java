package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.Locale;

/** 判断消息是否包含足量的非目标语言自然语言文本。 */
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

    private static final int MIN_LETTERS = 4;
    private static final float MIN_LETTER_RATIO = 0.5f;
    private static final float MIN_TARGET_SCRIPT_RATIO = 0.2f;

    private EnglishDetector() {
    }

    /** 与旧调用方兼容；默认目标语言为简体中文。 */
    public static Verdict evaluate(@Nullable String rawText, int textNodeCount,
                                   int opaqueNodeCount, boolean codeHeavy) {
        return evaluate(rawText, textNodeCount, opaqueNodeCount, codeHeavy,
                Config.DEF_TARGET_LANG);
    }

    public static Verdict evaluate(@Nullable String rawText, int textNodeCount,
                                   int opaqueNodeCount, boolean codeHeavy,
                                   @Nullable String targetLanguage) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new Verdict(false, "empty");
        }
        if (codeHeavy) {
            return new Verdict(false, "code-block");
        }

        String text = rawText.replaceAll("https?://\\S+", " ")
                .replaceAll("`[^`]*`", " ")
                .replaceAll(":([A-Za-z0-9_]+):", " ");

        int letters = 0;
        int nonSpace = 0;
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int targetLetters = 0;
        TargetScript targetScript = targetScript(targetLanguage);

        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                continue;
            }
            nonSpace++;
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN) {
                han++;
            } else if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                kana++;
            } else if (script == Character.UnicodeScript.HANGUL) {
                hangul++;
            }
            if (matchesTargetScript(script, targetScript)) {
                targetLetters++;
            }
        }

        if (nonSpace == 0) {
            return new Verdict(false, "no-visible-content");
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

        // Han-only text is treated as Chinese for the default target. Japanese kana
        // and Korean Hangul disambiguate their scripts from Chinese.
        if (targetScript == TargetScript.HAN
                && kana == 0 && hangul == 0
                && (float) han / letters >= MIN_TARGET_SCRIPT_RATIO) {
            return new Verdict(false, "target-language-script(chinese)");
        }
        if (targetScript == TargetScript.JAPANESE && kana > 0) {
            return new Verdict(false, "target-language-script(japanese)");
        }
        if (targetScript != TargetScript.HAN && targetScript != TargetScript.JAPANESE
                && targetScript != TargetScript.NONE
                && (float) targetLetters / letters >= MIN_TARGET_SCRIPT_RATIO) {
            return new Verdict(false, "target-language-script(" + targetLanguage + ")");
        }
        return new Verdict(true, "translatable(letters=" + letters
                + ",ratio=" + round(letterRatio) + ")");
    }

    private enum TargetScript {
        NONE, HAN, JAPANESE, HANGUL, CYRILLIC, ARABIC, GREEK, HEBREW,
        DEVANAGARI, BENGALI, THAI
    }

    private static TargetScript targetScript(@Nullable String language) {
        if (language == null) {
            return TargetScript.HAN;
        }
        String value = language.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return TargetScript.HAN;
        }
        if (containsAny(value, "中文", "汉语", "漢語", "chinese", "zh")) {
            return TargetScript.HAN;
        }
        if (containsAny(value, "日本", "japanese", "ja")) {
            return TargetScript.JAPANESE;
        }
        if (containsAny(value, "한국", "조선", "korean", "ko")) {
            return TargetScript.HANGUL;
        }
        if (containsAny(value, "рус", "russian", "ru", "укра", "ukrainian", "uk")) {
            return TargetScript.CYRILLIC;
        }
        if (containsAny(value, "العربية", "arabic", "ar", "فارسی", "persian", "farsi", "fa")) {
            return TargetScript.ARABIC;
        }
        if (containsAny(value, "ελλην", "greek", "el")) {
            return TargetScript.GREEK;
        }
        if (containsAny(value, "עברית", "hebrew", "he")) {
            return TargetScript.HEBREW;
        }
        if (containsAny(value, "हिन्दी", "hindi", "hi")) {
            return TargetScript.DEVANAGARI;
        }
        if (containsAny(value, "বাংলা", "bengali", "bn")) {
            return TargetScript.BENGALI;
        }
        if (containsAny(value, "ไทย", "thai", "th")) {
            return TargetScript.THAI;
        }
        return TargetScript.NONE;
    }

    private static boolean matchesTargetScript(Character.UnicodeScript script,
                                                TargetScript target) {
        switch (target) {
            case HAN:
                return script == Character.UnicodeScript.HAN;
            case JAPANESE:
                return script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA;
            case HANGUL:
                return script == Character.UnicodeScript.HANGUL;
            case CYRILLIC:
                return script == Character.UnicodeScript.CYRILLIC;
            case ARABIC:
                return script == Character.UnicodeScript.ARABIC;
            case GREEK:
                return script == Character.UnicodeScript.GREEK;
            case HEBREW:
                return script == Character.UnicodeScript.HEBREW;
            case DEVANAGARI:
                return script == Character.UnicodeScript.DEVANAGARI;
            case BENGALI:
                return script == Character.UnicodeScript.BENGALI;
            case THAI:
                return script == Character.UnicodeScript.THAI;
            default:
                return false;
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String round(float f) {
        return String.valueOf(Math.round(f * 100) / 100.0f);
    }
}
