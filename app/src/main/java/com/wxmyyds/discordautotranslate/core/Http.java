package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 客户端。
 *
 * <p><b>为什么不用 OkHttp</b>：本模块代码运行在 Discord 进程内，而 Discord 自身已打包
 * {@code okhttp3}（见 smali/okhttp3/）。若模块再打包一份会造成类冲突与版本错配。
 * 使用 JDK 自带的 {@link HttpURLConnection} 可做到零依赖、零冲突。
 * 阶段 3 如需更精细的 TLS/连接池控制再评估。</p>
 *
 * <p>超时由 {@link #timeoutMs} 控制，同时作用于连接与读取（需求 §5.5 超时时间）。</p>
 */
public final class Http {

    /** HTTP 响应。 */
    public static final class Response {
        public final int status;
        public final String body;
        /** Retry-After 头（秒），限流时可能提供。 */
        public final int retryAfterSeconds;

        Response(int status, String body, int retryAfterSeconds) {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /** 请求头。 */
    public static final class Headers {
        private final java.util.List<String[]> pairs = new java.util.ArrayList<>();

        public Headers add(String name, String value) {
            if (name != null && !name.isEmpty() && value != null) {
                pairs.add(new String[]{name, value});
            }
            return this;
        }

        /**
         * 解析“每行一条 Name: Value”的多行文本。
         * 非法行（缺少冒号）会被忽略并记账，由调用方决定是否提示。
         */
        public static Headers parse(@Nullable String multiLine) {
            Headers h = new Headers();
            if (multiLine == null) {
                return h;
            }
            int lineNo = 0;
            for (String raw : multiLine.split("\r?\n")) {
                lineNo++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    // 只记录行号与原因：该行可能包含未正确分隔的 Authorization 值。
                    XLog.d("ignore malformed header line (line=" + lineNo + ")");
                    continue;
                }
                h.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
            return h;
        }

        /** 查找已设置的请求头（大小写不敏感）。 */
        @Nullable
        public String get(String name) {
            for (String[] p : pairs) {
                if (p[0].equalsIgnoreCase(name)) {
                    return p[1];
                }
            }
            return null;
        }
    }

    private final int timeoutMs;

    /**
     * 正在执行中的请求连接，按执行线程索引。
     *
     * <p>{@link HttpURLConnection} 在读取阻塞期间并不会因为线程中断而立即返回，
     * 因此取消时必须由另一个线程关闭连接。热重载/关闭需要尽快停止在途请求，
     * 否则旧代次会继续占用线程与网络。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Http(int timeoutMs) {
        // 下限 1s，避免误配置成 0 导致立即超时。
        this.timeoutMs = Math.max(1000, timeoutMs);
    }

    /**
     * 中断某个执行线程上正在进行的请求：先关闭连接使阻塞读立即返回，再请求中断。
     *
     * <p>连接关闭只影响该线程自己的请求；已结束或不存在时无副作用。</p>
     *
     * @return true 表示存在并已关闭一个在途连接
     */
    public static boolean cancelRequestOn(Thread worker) {
        if (worker == null) {
            return false;
        }
        HttpURLConnection conn = ACTIVE.remove(worker);
        boolean closed = false;
        if (conn != null) {
            try {
                conn.disconnect();
                closed = true;
            } catch (Throwable ignored) {
                // 连接可能已被对端关闭。
            }
        }
        worker.interrupt();
        return closed;
    }

    /**
     * 发送 JSON POST 请求。
     *
     * @param sse 请求体含 {@code stream:true} 时为 true：按行读取并在
     *            {@code data: [DONE]} 处停止（不依赖服务端关流）。响应体
     *            原样返回（SSE 帧文本），由协议层解析累积 delta。
     *            <p>流式的意义：读超时按<b>字节间隔</b>计算，token 持续到达
     *            就不会触发；非流式接口生成完成前零字节返回，长生成会被
     *            总时长超时掐断（实测：慢速中转上长消息必超时）。</p>
     * @throws TranslationException 网络或超时错误（已归类）
     */
    public Response postJson(String url, String jsonBody, Headers headers, boolean sse)
            throws TranslationException {
        HttpURLConnection conn = null;
        final Thread worker = Thread.currentThread();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            ACTIVE.put(worker, conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", sse ? "text/event-stream" : "application/json");

            if (headers != null) {
                for (String[] p : headers.pairs) {
                    conn.setRequestProperty(p[0], p[1]);
                }
            }

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = sse ? readUntilDone(in) : readAll(in);
            int retryAfter = parseIntSafe(conn.getHeaderField("Retry-After"));

            return new Response(status, body, retryAfter);

        } catch (SocketTimeoutException e) {
            // 区分连接阶段与读阶段：读超时=服务端在预算内未回完响应
            //（非流式接口生成完成前不回字节，长生成会表现为读超时）。
            String phase = e.getMessage(); // "connect timed out" / "Read timed out"
            throw new TranslationException(TranslationException.Kind.TIMEOUT,
                    "请求超时（" + timeoutMs + "ms，" + (phase == null ? "未知阶段" : phase) + "）", e);
        } catch (IOException e) {
            throw new TranslationException(TranslationException.Kind.NETWORK,
                    "网络错误：" + e.getClass().getSimpleName(), e);
        } catch (Throwable t) {
            throw new TranslationException(TranslationException.Kind.UNKNOWN,
                    "请求失败：" + t.getClass().getSimpleName(), t);
        } finally {
            if (conn != null) {
                ACTIVE.remove(worker, conn);
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Nullable
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            // 上限 1MB，避免异常响应导致内存问题。
            int total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) {
                    throw new IOException("HTTP response exceeds 1 MiB limit");
                }
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    /**
     * 按行读取 SSE 响应体，读到 {@code data: [DONE]} 帧即停止。
     *
     * <p>不能读到流关闭为止：部分服务端发完 [DONE] 后保持连接不关，
     * 等关闭会一直阻塞到读超时。非流式 JSON 响应（服务端忽略 stream 参数）
     * 没有 [DONE]，读到 EOF 自然结束，行为与 {@link #readAll} 一致。</p>
     */
    @Nullable
    private static String readUntilDone(@Nullable InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 1024 * 1024) {
                    throw new IOException("HTTP response exceeds 1 MiB limit");
                }
                if ("data: [DONE]".equals(line.trim())) {
                    break;
                }
            }
            return sb.toString();
        }
    }

    private static int parseIntSafe(@Nullable String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
