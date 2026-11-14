package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EnglishDetectorTest {

    private static EnglishDetector.Verdict detect(String text, String target) {
        return EnglishDetector.evaluate(text, 1, 0, false, target);
    }

    @Test
    public void explicitDefaultTargetPreservesStructuralGuards() {
        assertFalse(EnglishDetector.evaluate(null, 1, 0, false,
                Config.DEF_TARGET_LANG).shouldTranslate);
        assertFalse(EnglishDetector.evaluate("hello world", 1, 0, true,
                Config.DEF_TARGET_LANG).shouldTranslate);
        assertFalse(EnglishDetector.evaluate("hello world", 0, 0, false,
                Config.DEF_TARGET_LANG).shouldTranslate);
        assertFalse(EnglishDetector.evaluate("hello world", 1, 4, false,
                Config.DEF_TARGET_LANG).shouldTranslate);
        assertTrue(EnglishDetector.evaluate("hello world", 1, 3, false,
                Config.DEF_TARGET_LANG).shouldTranslate);
    }

    @Test
    public void explicitDefaultTargetPreservesLanguageAndLengthGuards() {
        assertFalse(detect("hi", Config.DEF_TARGET_LANG).shouldTranslate);
        assertFalse(detect("今天天气很好", Config.DEF_TARGET_LANG).shouldTranslate);
        assertTrue(detect("Hello, how are you?", Config.DEF_TARGET_LANG).shouldTranslate);
    }

    @Test
    public void translatesRussianIntoChinese() {
        assertTrue(detect("Привет, как дела?", "简体中文").shouldTranslate);
    }

    @Test
    public void translatesJapaneseIntoChinese() {
        assertTrue(detect("こんにちは、元気ですか？", "简体中文").shouldTranslate);
    }

    @Test
    public void translatesKoreanIntoChinese() {
        assertTrue(detect("오늘 날씨가 정말 좋네요", "简体中文").shouldTranslate);
    }

    @Test
    public void skipsTextAlreadyInChineseTargetLanguage() {
        assertFalse(detect("今天天气很好", "简体中文").shouldTranslate);
    }

    @Test
    public void skipsTextAlreadyInJapaneseTargetLanguage() {
        assertFalse(detect("こんにちは、元気ですか？", "日本語").shouldTranslate);
    }

    @Test
    public void skipsTextAlreadyInRussianTargetLanguage() {
        assertFalse(detect("Привет, как дела?", "Русский").shouldTranslate);
    }

    @Test
    public void skipsTextAlreadyInBengaliTargetLanguage() {
        assertFalse(detect("বাংলা ভাষার একটি বাক্য", "বাংলা").shouldTranslate);
    }

    @Test
    public void preservesEnglishDetection() {
        assertTrue(detect("Hello, how are you?", "简体中文").shouldTranslate);
    }

    @Test
    public void ignoresLinksAndCodeWhenCountingLetters() {
        assertFalse(detect("https://example.com `someCode` !!!", "简体中文").shouldTranslate);
    }
}
