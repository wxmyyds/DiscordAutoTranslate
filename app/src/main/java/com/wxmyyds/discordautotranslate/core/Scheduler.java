package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 翻译调度：异步执行、并发限制、按“消息 ID + 内容版本”去重、可重试、丢弃过期结果。
 *
 * <p>需求映射：
 * <ul>
 *   <li>§5.4 后台异步、不阻塞主线程 → 线程池执行，结果经主线程回调。</li>
 *   <li>§5.4 限制并发 → {@link Semaphore} 按配置并发上限放行，可运行时变更。</li>
 *   <li>§5.4 去重 → {@link #inFlight} 以 messageId 为键。</li>
 *   <li>§5.4 超时/网络失败/HTTP 错误/限流/无效响应 → 由 {@link TranslationException} 分类。</li>
 *   <li>§风险 3 旧结果不得覆盖新译 → 回写前同时校验 generation 与 contentHash。</li>
 * </ul>
 */
public final class Scheduler {

    /** 翻译引擎抽象。实现必须在被中断时尽快返回。 */
    public interface Engine {
        /**
         * 同步执行翻译。
         *
         * @param text 待翻译原文
         * @return 译文
         * @throws Exception 网络/解析/配置错误（建议抛 {@link TranslationException}）
         */
        String translate(String text) throws Exception;
    }

    /** 结果回调，保证在主线程调用。 */
    public interface Callback {
        /**
         * @param accepted 结果是否被接受。false 表示已过期（消息被编辑、开关关闭、热重载或配置重置）。
         * @param error    失败原因；成功时为 null
         */
        void onResult(String messageId, boolean accepted,
                      @Nullable String translated, @Nullable Throwable error);
    }

    /** 结果接受判定。 */
    public interface Acceptance {
        boolean isStillValid(int generationSnapshot, String contentHash);
    }

    /** 线程池上限。实际并发由 {@link #permits} 按配置控制。 */
    private static final int POOL_MAX = 8;

    /** 可重试错误的最大尝试次数（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;

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
            0, POOL_MAX, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "dat-translate");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 并发闸门。permit 数等于配置的并发上限，可运行时调整。 */
    private final AdjustableSemaphore permits = new AdjustableSemaphore(Config.DEF_MAX_CONCURRENCY);
    private final AtomicInteger configuredConcurrency = new AtomicInteger(Config.DEF_MAX_CONCURRENCY);
    /** 串行化目标容量调整与 permit 释放，避免快速调高/调低造成许可数漂移。 */
    private final Object concurrencyLock = new Object();

    /**
     * 可缩减 permit 的信号量。
     *
     * <p>{@link Semaphore#reducePermits(int)} 是 <b>protected</b>，无法从外部类调用，
     * 因此通过子类暴露一个包内可见的封装。</p>
     */
    static final class AdjustableSemaphore extends Semaphore {
        AdjustableSemaphore(int permits) {
            super(permits, true);
        }

        void reduce(int reduction) {
            reducePermits(reduction);
        }
    }

    /** messageId -> 在途标记（用于去重）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger retried = new AtomicInteger();
    /** 是否已关闭；关闭后的实例拒绝新任务。 */
    private volatile boolean shutdown;

    public void shutdown() {
        shutdown = true;
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        inFlight.clear();
    }

    /**
     * 按配置调整并发上限。可在设置变更后调用，立即生效。
     * 仅在数值变化时调整 permit，避免影响在途任务。
     */
    public void setMaxConcurrency(int value) {
        int target = Math.max(1, Math.min(POOL_MAX, value));
        synchronized (concurrencyLock) {
            int current = configuredConcurrency.get();
            if (target == current) {
                return;
            }
            configuredConcurrency.set(target);
            if (target > current) {
                permits.release(target - current);
            } else {
                permits.reduce(current - target);
            }
        }
        XLog.i("max concurrency set to " + target);
    }

    public int completedCount() {
        return completed.get();
    }

    public int discardedCount() {
        return discarded.get();
    }

    public int failedCount() {
        return failed.get();
    }

    public int retriedCount() {
        return retried.get();
    }

    public String stats() {
        return "scheduler[completed=" + completed.get()
                + ", discarded=" + discarded.get()
                + ", failed=" + failed.get()
                + ", retried=" + retried.get()
                + ", inFlight=" + inFlight.size()
                + ", concurrency=" + configuredConcurrency.get() + "]";
    }

    /**
     * 提交翻译任务。带指数退避重试（仅对可重试错误）。
     *
     * @param messageId   消息 ID
     * @param contentHash 内容版本（含翻译配置版本）
     * @param text        原文
     * @param engine      翻译引擎
     * @param accept      结果接受判定
     * @param callback    主线程回调
     */
    public void submit(String messageId, String contentHash, String text,
                       Engine engine, Acceptance accept, Callback callback) {
        final int gen = StateStore.get().generation();

        // 同一消息已有在途任务则不重复提交（需求 §5.4 去重）。
        Boolean existing = inFlight.putIfAbsent(messageId, Boolean.TRUE);
        if (existing != null) {
            XLog.d("dedupe: task already in flight for " + messageId);
            return;
        }

        if (shutdown) {
            rejectSubmission(messageId, callback, "线程池已关闭");
            return;
        }
        try {
            pool.submit(() -> runTask(messageId, contentHash, text, engine, accept, callback, gen));
        } catch (Throwable t) {
            XLog.w("submit rejected for " + messageId + ": " + t.getClass().getSimpleName());
            rejectSubmission(messageId, callback, "线程池已满或已关闭");
        }
    }

    private void rejectSubmission(String messageId, Callback callback, String reason) {
        inFlight.remove(messageId);
        TranslationException error = new TranslationException(
                TranslationException.Kind.UNKNOWN, reason);
        MainThread.post(() -> callback.onResult(messageId, true, null, error));
    }

    private void runTask(String messageId, String contentHash, String text,
                         Engine engine, Acceptance accept, Callback callback, int gen) {
        String result = null;
        Throwable error = null;
        try {
            result = translateWithRetry(messageId, text, engine, accept, gen, contentHash);
        } catch (Throwable t) {
            error = t;
        } finally {
            inFlight.remove(messageId);
        }

        final String fr = result;
        final Throwable fe = error;

        MainThread.post(() -> {
            boolean ok = accept.isStillValid(gen, contentHash);
            if (ok) {
                if (fe == null) {
                    completed.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            } else {
                discarded.incrementAndGet();
                XLog.d("discard stale translation result for " + messageId);
            }
            callback.onResult(messageId, ok, fr, fe);
        });
    }

    /**
     * 带指数退避的重试。仅对 {@link TranslationException#isRetryable()} 为 true 的错误重试，
     * 并在重试前检查任务是否已失效，避免为已过期内容浪费请求（需求 §5.4）。
     */
    @Nullable
    private String translateWithRetry(String messageId, String text, Engine engine,
                                      Acceptance accept, int gen, String contentHash)
            throws Throwable {
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            // 提交前已失效则直接放弃，不发请求。
            if (!accept.isStillValid(gen, contentHash)) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已失效");
            }

            boolean acquired = false;
            try {
                // 并发闸门；被中断时立即退出。
                permits.acquire();
                acquired = true;
                return engine.translate(text);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消", ie);
            } catch (Throwable t) {
                last = t;
                if (!isRetryable(t) || attempt == MAX_ATTEMPTS) {
                    throw t;
                }
                retried.incrementAndGet();
                long backoff = backoffMillis(attempt, t);
                XLog.w("attempt " + attempt + "/" + MAX_ATTEMPTS + " failed for " + messageId
                        + " (" + t.getClass().getSimpleName() + "), retry in " + backoff + "ms");
                if (!sleepInterruptibly(backoff)) {
                    throw new TranslationException(TranslationException.Kind.CANCELLED,
                            "重试等待被中断", t);
                }
            } finally {
                if (acquired) {
                    synchronized (concurrencyLock) {
                        permits.release();
                    }
                }
            }
        }
        throw last != null ? last
                : new TranslationException(TranslationException.Kind.UNKNOWN, "未知失败");
    }

    private static boolean isRetryable(Throwable t) {
        return t instanceof TranslationException
                && ((TranslationException) t).isRetryable();
    }

    /** 退避时长：1s、2s……并尊重服务端 Retry-After（若已被转换进消息）。 */
    private static long backoffMillis(int attempt, Throwable t) {
        if (t instanceof TranslationException) {
            int status = ((TranslationException) t).httpStatus();
            if (status == 429) {
                // 限流时给更长的退避，避免加剧限流。
                return Math.min(8000L, 2000L * (1L << (attempt - 1)));
            }
        }
        return Math.min(4000L, 1000L * (1L << (attempt - 1)));
    }

    /** @return true 表示正常睡满；false 表示被中断 */
    private static boolean sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
            // 理论不可达；退化为稳定但非加密强度的哈希。
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
