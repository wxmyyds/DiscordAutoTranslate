package com.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.Http;
import com.wxmyyds.discordautotranslate.core.JsonPath;
import com.wxmyyds.discordautotranslate.core.JsonTemplate;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.TranslationException;
import com.wxmyyds.discordautotranslate.core.XLog;

/**
 * 通用 JSON 翻译引擎（需求 §5.5）。
 *
 * <p>完全由配置驱动，不绑定任何特定服务商：
 * <ol>
 *   <li>用 {@link JsonTemplate} 渲染请求体（{@code {{text}}} / {@code {{target}}} / {@code {{model}}}）。</li>
 *   <li>用 {@link Http} 发送 POST，超时可配。</li>
 *   <li>用 {@link JsonPath} 按配置的路径提取译文。</li>
 * </ol>
 *
 * <p>默认配置适配 OpenAI 兼容的 {@code /chat/completions}
 * （见 {@link Config#DEF_BODY_TEMPLATE} 与 {@link Config#DEF_RESPONSE_PATH}）。</p>
 *
 * <p>不承诺兼容所有接口：配置不完整或响应结构不匹配时通过
 * {@link TranslationException} 给出明确错误（需求 §5.5）。</p>
 */
public final class JsonApiEngine implements Scheduler.Engine {

    private final Config.Snapshot cfg;
    private final Http http;

    public JsonApiEngine(Config.Snapshot cfg) {
        this.cfg = cfg;
        this.http = new Http(cfg.timeoutMs);
    }

    @Override
    public String translate(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.EMPTY, "原文为空，无需翻译");
        }
        validate();

        // 1) 渲染请求体
        JsonTemplate.RenderResult rendered = JsonTemplate.render(
                cfg.bodyTemplate, text, cfg.targetLang, cfg.model, cfg.systemPrompt);
        if (rendered.hasUnknownPlaceholders()) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "请求模板含未知占位符：" + String.join(", ", rendered.unknownPlaceholders));
        }

        // 2) 组装请求头（认证 + 用户自定义头）
        Http.Headers headers = buildHeaders();

        // 3) 发送（任务被取消时通过线程中断尽早退出）
        if (Thread.currentThread().isInterrupted()) {
            throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
        }
        XLog.d("POST " + cfg.apiUrl + " text=" + XLog.text(text));
        Http.Response resp = http.postJson(cfg.apiUrl, rendered.body, headers);

        // 4) 状态码归类
        if (!resp.isSuccess()) {
            throw classifyHttpError(resp);
        }

        // 5) 按路径提取译文
        String translated = JsonPath.extractString(resp.body, cfg.responsePath);
        if (translated == null || translated.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.EMPTY, "提取到的译文为空");
        }
        return translated.trim();
    }

    /** 配置校验：任一必需项缺失都给出具体提示（需求 §5.5、§8）。 */
    private void validate() throws TranslationException {
        if (cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "API URL 未配置");
        }
        String u = cfg.apiUrl.trim().toLowerCase(java.util.Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new TranslationException(TranslationException.Kind.CONFIG,
                    "API URL 必须以 http:// 或 https:// 开头");
        }
        if (cfg.bodyTemplate == null || cfg.bodyTemplate.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "请求模板未配置");
        }
        if (cfg.responsePath == null || cfg.responsePath.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.CONFIG, "响应提取路径未配置");
        }
        JsonPath.validateJson(cfg.bodyTemplate, "请求模板");
    }

    /** 构造请求头。若用户已自定义 Authorization，则不覆盖。 */
    private Http.Headers buildHeaders() {
        Http.Headers headers = Http.Headers.parse(cfg.extraHeaders);
        if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()
                && headers.get("Authorization") == null) {
            headers.add("Authorization", "Bearer " + cfg.apiKey.trim());
        }
        return headers;
    }

    /** 把 HTTP 错误映射为可读的 {@link TranslationException}。 */
    private TranslationException classifyHttpError(Http.Response resp) {
        String detail = extractErrorMessage(resp.body);
        int status = resp.status;

        if (status == 429) {
            String hint = resp.retryAfterSeconds > 0
                    ? "，建议 " + resp.retryAfterSeconds + "s 后重试" : "";
            return new TranslationException(TranslationException.Kind.RATE_LIMIT,
                    "限流" + hint + (detail == null ? "" : "：" + detail), status, null);
        }
        if (status == 401 || status == 403) {
            return new TranslationException(TranslationException.Kind.HTTP,
                    "认证失败（请检查 API 密钥）" + (detail == null ? "" : "：" + detail),
                    status, null);
        }
        if (status >= 500) {
            return new TranslationException(TranslationException.Kind.HTTP,
                    "服务端错误" + (detail == null ? "" : "：" + detail), status, null);
        }
        return new TranslationException(TranslationException.Kind.HTTP,
                detail, status, null);
    }

    /**
     * 从错误响应中尽量提取可读信息。
     * 兼容 OpenAI 风格 {@code {"error":{"message":"..."}}} 与通用 {@code message} 字段。
     */
    @Nullable
    private static String extractErrorMessage(@Nullable String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            String msg = JsonPath.extractString(body, "error.message");
            if (msg != null && !msg.isEmpty()) {
                return truncate(msg);
            }
        } catch (Throwable ignored) {
        }
        try {
            String msg = JsonPath.extractString(body, "message");
            if (msg != null && !msg.isEmpty()) {
                return truncate(msg);
            }
        } catch (Throwable ignored) {
        }
        return truncate(body);
    }

    private static String truncate(String s) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
