package com.wxmyyds.discordautotranslate.translate;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.Http;
import com.wxmyyds.discordautotranslate.core.JsonPath;
import com.wxmyyds.discordautotranslate.core.JsonTemplate;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.Segments;
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
        // 带分段标记的请求需要模型知道标记规则；追加在系统提示词末尾，
        // 而不改动用户配置的提示词本身（避免影响配置版本与既有判定）。
        String systemPrompt = cfg.systemPrompt;
        if (Segments.isSegmented(text)) {
            systemPrompt = (systemPrompt == null || systemPrompt.trim().isEmpty()
                    ? "" : systemPrompt + "\n\n") + Segments.RULE;
        }
        JsonTemplate.RenderResult rendered = JsonTemplate.render(
                cfg.bodyTemplate, text, cfg.targetLang, cfg.model, systemPrompt);
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
        XLog.d("POST " + XLog.safeUrl(cfg.apiUrl) + " text=" + XLog.text(text));
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
        // 【临时诊断，定位后删除】只输出结构计数，不输出正文。
        com.wxmyyds.discordautotranslate.hooks.ParagraphProbe
                .logTranslation("engine-raw", translated);
        return translated.trim();
    }

    /**
     * 取消正在进行中的请求：关闭该线程上的连接，让阻塞读立即返回，
     * 否则热重载/关闭后旧代次的请求会继续跑到超时。
     */
    @Override
    public void cancel(Thread worker) {
        Http.cancelRequestOn(worker);
    }

    /** 配置校验：任一必需项缺失都给出具体提示（需求 §5.5、§8）。 */
    private void validate() throws TranslationException {
        validateConfig(cfg);
    }

    /**
     * 校验一份配置是否可用于发起请求。
     *
     * <p>抽成静态方法供设置页的离线自检复用，避免两处各写一套校验而产生不一致。</p>
     */
    public static void validateConfig(Config.Snapshot cfg) throws TranslationException {
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
        // 校验渲染后的请求体，而不是含 {{placeholder}} 的模板原文：
        // 直接校验模板原文会把占位符误判为非法 JSON。
        JsonTemplate.validate(cfg.bodyTemplate, cfg.targetLang, cfg.model, cfg.systemPrompt);
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

    /** 把 HTTP 错误映射为用户可读、且不暴露服务端响应正文的异常。 */
    private TranslationException classifyHttpError(Http.Response resp) {
        int status = resp.status;
        if (status == 429) {
            String hint = resp.retryAfterSeconds > 0
                    ? "，建议 " + resp.retryAfterSeconds + "s 后重试" : "";
            return new TranslationException(TranslationException.Kind.RATE_LIMIT,
                    "限流" + hint, status, resp.retryAfterSeconds, null);
        }
        if (status == 401 || status == 403) {
            return new TranslationException(TranslationException.Kind.HTTP,
                    "认证失败（请检查 API 密钥）", status, null);
        }
        if (status >= 500) {
            return new TranslationException(TranslationException.Kind.HTTP,
                    "服务端错误", status, null);
        }
        return new TranslationException(TranslationException.Kind.HTTP,
                "请求失败", status, null);
    }
}