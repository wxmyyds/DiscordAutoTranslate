package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 翻译调度：异步执行、并发限制、按“消息 ID + 内容版本”去重、丢弃过期结果。
 *
 * <p>需求映射：
 * <ul>
 *   <li>§5.4 后台异步、不阻塞主线程 → 固定线程池 + 主线程线程回调。</li>
 *   <li>§5.4 限制并发 → 见 {@link #MAX_CONCURRENCY}。</li>
 *   <li>§5.4 去重 → {@link #inFlight} 以 messageId 为键。</li>
 *   <li>§风险 3 旧结果不得覆盖新译 → 回写前同时校验 generation 与 contentHash。</li>
 * </ul>
 *
 * <p>阶段 1 说明：真正的 HTTP 翻译引擎属于阶段 2（见 docs/design-stage1.md）。
 * 本类只定义调度骨架，翻译实现由 {@link Engine} 注入。</p>
 */
public final class Scheduler {

    /** 翻译引擎抽象，阶段 2 会提供 OpenAI 兼容实现。 */
    public interface Engine {
        /**
         * 同步执行翻译。实现必须在被中断时尽快返回。
         *
         * @param text 待翻译原文
         * @return 译文
         * @throws Exception 网络/解析/配置错误
         */
        String translate(String text) throws Exception;
    }

    /** 结果回调，保证在主线程调用。 */
    public interface Callback {
        /**
         * @param accepted 结果是否被接受。false 表示已过期（消息被编辑、开关关闭或模块已热重载）。
         */
        void onResult(String messageId, boolean accepted, @Nullable String translated, @Nullable String error);
    }

    private static final int MAX_CONCURRENCY = 4;

    /** 进程级单例。热重载时由旧实例 shutdown 后重建。 */
    private static volatile Scheduler sInstance;

    public static Scheduler get() {
        Scheduler s = sInstance;
        if (s == null) {
            synchronized (Scheduler.class) {
                s = sInstance;
                if (s == null) {
                    s = new Scheduler();
                    sInstance = s;
                }
            }
        }
        return s;
    }

    /** 热重载：关闭并丢弃当前实例。 */
    public static void reset() {
        synchronized (Scheduler.class) {
            if (sInstance != null) {
                sInstance.shutdown();
                sInstance = null;
            }
        }
    }

    private final ExecutorService pool = new ThreadPoolExecutor(
            0, MAX_CONCURRENCY, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "dat-translate");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** messageId -> 在途任务的 generation 快照，用于去重与取消。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();

    /** 关闭调度器，热重载时由旧实例调用。 */
    public void shutdown() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        inFlight.clear();
    }

    public int completedCount() {
        return completed.get();
    }

    public int discardedCount() {
        return discarded.get();
    }

    /**
     * 提交翻译任务。
     *
     * @param messageId   消息 ID
     * @param contentHash 内容版本（含翻译配置版本）
     * @param text        原文
     * @param engine      翻译引擎
     * @param accept      结果接受判定；在后台线程与主线程各评估一次
     * @param callback    主线程回调
     */
    public void submit(String messageId, String contentHash, String text,
                       Engine engine, Acceptance accept, Callback callback) {
        int gen = StateStore.get().generation();

        // 同一消息已有在途任务则不重复提交（需求 §5.4 去重）。
        Integer existing = inFlight.putIfAbsent(messageId, gen);
        if (existing != null) {
            XLog.d("dedupe: task already in flight for " + messageId);
            return;
        }

        Future<?> f;
        try {
            f = pool.submit(() -> {
                String result = null;
                String error = null;
                try {
                    result = engine.translate(text);
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + ": " + t.getMessage();
                } finally {
                    inFlight.remove(messageId);
                }

                final String fr = result;
                final String fe = error;

                MainThread.post(() -> {
                    boolean ok = accept.isStillValid(gen, contentHash);
                    if (ok) {
                        completed.incrementAndGet();
                    } else {
                        discarded.incrementAndGet();
                        XLog.d("discard stale translation result for " + messageId);
                    }
                    callback.onResult(messageId, ok, fr, fe);
                });
            });
        } catch (Throwable t) {
            // 线程池已满或已关闭：不做重试，保留原文即可（需求 §2.6 优雅降级）。
            inFlight.remove(messageId);
            XLog.w("submit rejected for " + messageId + ": " + t.getClass().getSimpleName());
            MainThread.post(() -> callback.onResult(messageId, false, null, "rejected"));
            return;
        }

        // 任务被取消时清理在途标记。
        if (f.isCancelled()) {
            inFlight.remove(messageId);
        }
    }

    /** 结果接受判定。 */
    public interface Acceptance {
        boolean isStillValid(int generationSnapshot, String contentHash);
    }

    /** 稳定内容哈希：内容 + 配置版本（需求 §5.6 缓存键要求）。 */
    public static String contentHash(String text, String configVersion) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(text.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(configVersion.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // 理论上不会发生；退化为内容本身，仍保持稳定性。
            return Integer.toHexString((text + "|" + configVersion).hashCode());
        }
    }

    /** 主线程投递工具。Discord 消息渲染全部发生在主线程。 */
    public static final class MainThread {
        private static volatile android.os.Handler sHandler;

        private MainThread() {
        }

        public static void install() {
            if (sHandler == null) {
                sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
        }

        public static void clear() {
            sHandler = null;
        }

        public static void post(Runnable r) {
            android.os.Handler h = sHandler;
            if (h == null) {
                // 未安装时（理论上不应发生）直接执行，避免任务丢失。
                r.run();
                return;
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                r.run();
            } else {
                h.post(r);
            }
        }
    }
}
