package com.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.JsonPath;
import com.wxmyyds.discordautotranslate.core.TranslationException;

/**
 * 连接测试与测试翻译（需求 §3.1、§5.5、§8：可测试 API 连接和实际翻译）。
 *
 * <p>由设置界面调用，同步执行，调用方需自行放到后台线程。
 * 返回结构化结果以便界面显示明确错误。</p>
 */
public final class ApiTester {

    /** 测试结果。 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        /** 测试翻译时的译文（仅成功时有值）。 */
        @Nullable
        public final String translated;
        /** 耗时毫秒。 */
        public final long elapsedMs;

        Result(boolean ok, String message, @Nullable String translated, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.translated = translated;
            this.elapsedMs = elapsedMs;
        }
    }

    /** 默认测试文本：包含引号、换行、反斜杠，同时验证模板转义是否正确。 */
    public static final String DEFAULT_TEST_TEXT =
            "Hello, \"world\"\\\nThis is a test.";

    private ApiTester() {
    }

    /**
     * 测试连接：只做配置校验 + 一次真实请求。
     *
     * <p>说明：通用 JSON 配置没有统一的“健康检查”端点，因此连接测试等价于
     * 用一段固定文本执行一次真实翻译请求，并校验响应能被解析。
     * 这样能同时覆盖 URL、认证、模板、提取路径四项配置。</p>
     */
    public static Result testConnection(Config.Snapshot cfg) {
        return testTranslation(cfg, DEFAULT_TEST_TEXT);
    }

    /** 测试翻译：用指定文本走完整流程，返回译文或明确的错误信息。 */
    public static Result testTranslation(Config.Snapshot cfg, String text) {
        long start = System.currentTimeMillis();
        try {
            // 与真实请求共用同一套配置校验，避免规则不一致。
            JsonApiEngine.validateConfig(cfg);

            JsonApiEngine engine = new JsonApiEngine(cfg);
            String translated = engine.translate(text);

            long ms = System.currentTimeMillis() - start;
            return new Result(true, "成功（" + ms + "ms）", translated, ms);

        } catch (TranslationException e) {
            long ms = System.currentTimeMillis() - start;
            return new Result(false, e.userMessage(), null, ms);
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - start;
            return new Result(false,
                    "失败：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage()),
                    null, ms);
        }
    }

    /**
     * 离线自检：不发起网络请求，只校验配置本身的完整性。
     * 供设置界面在无网络时给出有用的提示。
     */
    public static Result selfCheck(Config.Snapshot cfg) {
        long start = System.currentTimeMillis();
        try {
            // 与真实请求共用同一套配置校验，避免两处规则不一致。
            JsonApiEngine.validateConfig(cfg);

            // 内置样例只覆盖默认 OpenAI 提取路径；自定义路径不在此处误判为错误，
            // 真实结构由在线“测试连接/测试翻译”确认。
            boolean sampleMatchesPath = Config.DEF_RESPONSE_PATH.equals(cfg.responsePath)
                    && pathExistsInSample(cfg.responsePath);

            long ms = System.currentTimeMillis() - start;
            return new Result(true, sampleMatchesPath
                    ? "配置检查通过（" + ms + "ms）"
                    : "模板/URL/路径格式有效；内置响应样例无法验证此自定义路径（" + ms + "ms）",
                    null, ms);
        } catch (TranslationException e) {
            return new Result(false, e.userMessage(), null,
                    System.currentTimeMillis() - start);
        } catch (Throwable t) {
            return new Result(false, "配置检查失败：" + t.getClass().getSimpleName(),
                    null, System.currentTimeMillis() - start);
        }
    }

    /** 仅在内置样例确实有该路径时返回 true；否则不将自定义路径误判为错误。 */
    private static boolean pathExistsInSample(String path) {
        try {
            JsonPath.extractString(simulatedResponse(), path);
            return true;
        } catch (TranslationException e) {
            return false;
        }
    }

    /** 固定样例只用于默认 OpenAI 提取路径的离线示意。 */
    private static String simulatedResponse() {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"译文\"}}],\"data\":{\"translations\":[{\"text\":\"译文\","
                + "\"translatedText\":\"译文\"}]}}";
    }
}
