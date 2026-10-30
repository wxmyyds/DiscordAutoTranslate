package io.github.wxmyyds.discordautotranslate.core;

/**
 * 翻译流程中的可分类异常。
 *
 * <p>需求 §5.5 / §8：配置错误、网络错误、超时、限流和无效响应都必须有明确反馈。
 * 因此所有失败路径都归一到 {@link Kind}，由 {@link #userMessage()} 给出可读提示。</p>
 */
public class TranslationException extends Exception {

    public enum Kind {
        /** 配置缺失或不合法（URL 为空、URL 协议头不符、请求体构造失败等）。 */
        CONFIG,
        /** 连接失败、DNS 失败、TLS 失败等网络层错误。 */
        NETWORK,
        /** 读取超时或连接超时。 */
        TIMEOUT,
        /** 收到非 2xx 的 HTTP 状态码。 */
        HTTP,
        /** 429 或服务端明确提示限流。 */
        RATE_LIMIT,
        /** 响应不是合法 JSON，或按协议结构取不到译文。 */
        PARSE,
        /** 请求成功但译文为空。 */
        EMPTY,
        /** 任务在完成前被取消（关闭开关、消息被编辑、模块热重载）。 */
        CANCELLED,
        /** 其他未归类错误。 */
        UNKNOWN
    }

    private final Kind kind;
    private final int httpStatus;

    public TranslationException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public TranslationException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public TranslationException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 面向用户的可读提示（设置页与诊断日志共用）。 */
    public String userMessage() {
        switch (kind) {
            case CONFIG:
                return "配置错误：" + getMessage();
            case NETWORK:
                return "网络连接失败，请检查网络后重试。";
            case TIMEOUT:
                return "请求超时，可在设置中增大超时时间后重试。";
            case RATE_LIMIT:
                return "API 限流（429），请稍后重试或降低并发。";
            case HTTP:
                return "服务返回错误状态码 " + httpStatus
                        + (getMessage() == null ? "" : "（" + getMessage() + "）");
            case PARSE:
                return "响应解析失败：" + getMessage();
            case EMPTY:
                return "服务返回的译文为空。";
            case CANCELLED:
                return "请求已取消。";
            default:
                return "翻译失败：" + getMessage();
        }
    }
}
