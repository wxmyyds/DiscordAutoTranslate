package com.wxmyyds.discordautotranslate.translate;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.Http;
import com.wxmyyds.discordautotranslate.core.Protocols;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.Segments;
import com.wxmyyds.discordautotranslate.core.TranslationException;
import com.wxmyyds.discordautotranslate.core.XLog;

/**
 * JSON 翻译引擎（需求 §5.5）。
 *
 * <p>按配置的供应商协议工作，不绑定任何特定服务商：
 * <ol>
 *   <li>用 {@link Protocols#requestBody} 构造请求体（协议决定格式）。</li>
 *   <li>用 {@link Http} 发送 POST，超时可配；端点由 {@link Protocols#endpoint} 按协议解析。</li>
 *   <li>用 {@link Protocols#extractTranslation} 按协议提取译文。</li>
 * </ol>
 *
 * <p>默认协议为 OpenAI 兼容 {@code /chat/completions}
 * （见 {@link Config#DEF_PROTOCOL}）。</p>
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
        validateConfig(cfg);
        // 单一链路（用户决策）：一次无标记纯文本请求，不重试、不降级。
        // 失败由调度器的既有错误重试处理；译文按行回填到各文本节点。
        return requestOnce(text);
    }

    /**
     * 发送一次请求并返回协议提取后的译文。
     *
     * <p>按协议构造请求体（请求文本若带分段标记会追加分段协议说明到系统
     * 提示词末尾；单链路下请求恒为无标记纯文本，该分支不触发），发送后
     * 按协议提取译文。</p>
     */
    private String requestOnce(String text) throws Exception {
        // 1) 按协议构造请求体（无标记请求不追加分段协议说明）
        String body = buildBody(text);
        Http.Headers headers = buildHeaders();

        // 2) 发送（任务被取消时通过线程中断尽早退出）
        if (Thread.currentThread().isInterrupted()) {
            throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
        }
        String url = Protocols.endpoint(cfg.protocol, cfg.apiUrl);
        XLog.d("POST " + XLog.safeUrl(url) + " text=" + XLog.text(text));
        long t0 = System.currentTimeMillis();
        // Compatible 协议请求体带 stream:true，按 SSE 读取（读到 [DONE] 为止）；
        // Responses 协议保持非流式。
        boolean sse = !Config.PROTOCOL_OPENAI_RESPONSES.equals(cfg.protocol);
        Http.Response resp = http.postJson(url, body, headers, sse);
        XLog.d("POST round-trip " + (System.currentTimeMillis() - t0) + "ms");

        // 3) 状态码归类
        if (!resp.isSuccess()) {
            throw classifyHttpError(resp);
        }

        // 4) 按协议提取译文
        long t1 = System.currentTimeMillis();
        String translated = Protocols.extractTranslation(cfg.protocol, resp.body);
        XLog.d("response parsed in " + (System.currentTimeMillis() - t1) + "ms"
                + " (body=" + resp.body.length() + " chars)");
        if (translated == null || translated.trim().isEmpty()) {
            throw new TranslationException(TranslationException.Kind.EMPTY, "提取到的译文为空");
        }
        return translated.trim();
    }

    /** 构造一次请求体：请求文本带分段标记时追加分段协议说明（单链路下不触发）。 */
    private String buildBody(String text) throws Exception {
        String systemPrompt = cfg.systemPrompt;
        if (Segments.isSegmented(text)) {
            systemPrompt = (systemPrompt == null || systemPrompt.trim().isEmpty()
                    ? "" : systemPrompt + "\n\n") + Segments.RULE;
        }
        return Protocols.requestBody(cfg.protocol, text, systemPrompt, cfg.model);
    }

    /**
     * 取消正在进行中的请求：关闭该线程上的连接，让阻塞读立即返回，
     * 否则热重载/关闭后旧代次的请求会继续跑到超时。
     */
    @Override
    public void cancel(Thread worker) {
        Http.cancelRequestOn(worker);
    }

    /**
     * 校验一份配置是否可用于发起请求。
     *
     * <p>抽成静态方法供设置页的离线自检复用，避免两处各写一套校验而产生不一致。
     * 请求体与译文提取由 {@link Protocols} 按协议内置实现，无需再校验模板与路径。</p>
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