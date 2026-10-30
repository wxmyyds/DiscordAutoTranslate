package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EnglishDetectorTest {

    private static EnglishDetector.Verdict detect(String text, String target) {
        return EnglishDetector.evaluate(text, 1, 0, false, target);
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
    public void preservesEnglishDetection() {
        assertTrue(detect("Hello, how are you?", "简体中文").shouldTranslate);
    }

    @Test
    public void ignoresLinksAndCodeWhenCountingLetters() {
        assertFalse(detect("https://example.com `someCode` !!!", "简体中文").shouldTranslate);
    }
}
