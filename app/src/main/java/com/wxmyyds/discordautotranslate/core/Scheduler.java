package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

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
 *   <li>§5.4 去重 → {@link #inFlight} 以消息 ID、内容哈希与 generation 为键。</li>
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

        /** 取消由此引擎执行、且当前绑定到 worker 的请求。 */
        default void cancel(Thread worker) {
            worker.interrupt();
        }
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

    /**
     * 热重载专用：关闭当前实例并等待线程结束。
     *
     * <p>LSPosed 要求 {@code onHotReloading} 返回 true 前停止本模块拥有的线程；
     * 仅调用 {@link #reset()} 只是请求停止，无法确认旧线程已退出。</p>
     *
     * @return true 表示线程已在超时内终止
     */
    public static boolean resetAndAwait(long timeoutMs) {
        Scheduler instance;
        synchronized (Scheduler.class) {
            instance = sInstance;
            sInstance = null;
        }
        if (instance == null) {
            return true;
        }
        return instance.shutdownAndAwait(timeoutMs);
    }

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
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

    /** 任务身份包含消息、内容版本与模块代次。 */
    private static final class TaskKey {
        final String messageId;
        final String contentHash;
        final int generation;

        TaskKey(String messageId, String contentHash, int generation) {
            this.messageId = messageId;
            this.contentHash = contentHash;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TaskKey)) return false;
            TaskKey that = (TaskKey) other;
            return generation == that.generation
                    && messageId.equals(that.messageId)
                    && contentHash.equals(that.contentHash);
        }

        @Override
        public int hashCode() {
            int result = messageId.hashCode();
            result = 31 * result + contentHash.hashCode();
            result = 31 * result + generation;
            return result;
        }
    }

    /** 保存任务执行时所需的数据，并记录当前执行线程以便引擎取消请求。 */
    private static final class Task {
        final TaskKey key;
        final String text;
        final Engine engine;
        final Acceptance acceptance;
        final Callback callback;
        volatile Thread worker;

        Task(TaskKey key, String text, Engine engine, Acceptance acceptance, Callback callback) {
            this.key = key;
            this.text = text;
            this.engine = engine;
            this.acceptance = acceptance;
            this.callback = callback;
        }
    }

    /** task key -> 在途任务；按 key-value 移除，避免旧任务删除同 key 的新标记。 */
    private final java.util.concurrent.ConcurrentHashMap<TaskKey, Task> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger retried = new AtomicInteger();
    /** 是否已关闭；关闭后的实例拒绝新任务并抑制未交付回调。 */
    private volatile boolean shutdown;
    private final Object lifecycleLock = new Object();

    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shutdown) return;
            shutdown = true;
        }

        java.util.List<Task> active = new java.util.ArrayList<>(inFlight.values());
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        for (Task task : active) {
            Thread worker = task.worker;
            if (worker != null) {
                try {
                    task.engine.cancel(worker);
                } catch (Throwable t) {
                    XLog.w("engine cancellation failed for " + task.key.messageId, t);
                }
            }
        }
        inFlight.clear();
    }

    /** 关闭并等待线程池终止；超时或当前线程被中断时返回 false。 */
    public boolean shutdownAndAwait(long timeoutMs) {
        shutdown();
        try {
            return pool.awaitTermination(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        final Task task = new Task(new TaskKey(messageId, contentHash, gen),
                text, engine, accept, callback);

        if (shutdown) return;
        Task existing = inFlight.putIfAbsent(task.key, task);
        if (existing != null) {
            XLog.d("dedupe: task already in flight for " + messageId + " / " + contentHash);
            return;
        }
        if (shutdown) {
            inFlight.remove(task.key, task);
            return;
        }
        try {
            pool.execute(() -> runTask(task));
        } catch (Throwable t) {
            inFlight.remove(task.key, task);
            if (shutdown) return;
            XLog.w("submit rejected for " + messageId + ": " + t.getClass().getSimpleName());
            TranslationException error = new TranslationException(
                    TranslationException.Kind.UNKNOWN, "线程池已满或已关闭");
            postResult(task, null, error, false);
        }
    }

    private void runTask(Task task) {
        task.worker = Thread.currentThread();
        String result = null;
        Throwable error = null;
        try {
            if (shutdown) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            result = translateWithRetry(task);
        } catch (Throwable t) {
            error = t;
        } finally {
            task.worker = null;
            inFlight.remove(task.key, task);
        }

        if (!shutdown) {
            postResult(task, result, error, true);
        }
    }

    /** 主线程交付时再次检查 shutdown 与内容接受状态。 */
    private void postResult(Task task, @Nullable String translated,
                            @Nullable Throwable error, boolean countCompletion) {
        MainThread.post(() -> {
            synchronized (lifecycleLock) {
                if (shutdown) return;
                boolean accepted = isStillValid(task);
                if (shutdown) return;
                if (countCompletion) {
                    if (accepted) {
                        if (error == null) {
                            completed.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } else {
                        discarded.incrementAndGet();
                        XLog.d("discard stale translation result for " + task.key.messageId);
                    }
                } else if (!accepted) {
                    discarded.incrementAndGet();
                }
                task.callback.onResult(task.key.messageId, accepted, translated, error);
            }
        });
    }

    private boolean isStillValid(Task task) {
        try {
            return task.acceptance.isStillValid(task.key.generation, task.key.contentHash);
        } catch (Throwable t) {
            XLog.w("acceptance check failed for " + task.key.messageId, t);
            return false;
        }
    }

    /**
     * 带指数退避的重试。仅对 {@link TranslationException#isRetryable()} 为 true 的错误重试，
     * 并在重试前检查任务是否已失效，避免为已过期内容浪费请求（需求 §5.4）。
     */
    @Nullable
    private String translateWithRetry(Task task) throws Throwable {
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (shutdown || Thread.currentThread().isInterrupted()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }

            boolean acquired = false;
            Throwable attemptFailure = null;
            try {
                // 并发闸门；仅在获取许可后确认任务仍有效，避免等待许可期间发出过期请求。
                permits.acquire();
                acquired = true;
                // 检查必须发生在获得许可之后；等待许可期间失效的任务不会发请求。
                if (shutdown || Thread.currentThread().isInterrupted()) {
                    throw new TranslationException(
                            TranslationException.Kind.CANCELLED, "任务已取消");
                }
                if (!isStillValid(task)) {
                    throw new TranslationException(
                            TranslationException.Kind.CANCELLED, "任务已失效");
                }
                if (shutdown || Thread.currentThread().isInterrupted()) {
                    throw new TranslationException(
                            TranslationException.Kind.CANCELLED, "任务已取消");
                }
                return task.engine.translate(task.text);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消", ie);
            } catch (Throwable t) {
                attemptFailure = t;
            } finally {
                if (acquired) {
                    synchronized (concurrencyLock) {
                        permits.release();
                    }
                }
            }

            last = attemptFailure;
            if (!isRetryable(attemptFailure) || attempt == MAX_ATTEMPTS) {
                throw attemptFailure;
            }
            retried.incrementAndGet();
            long backoff = backoffMillis(attempt, attemptFailure);
            XLog.w("attempt " + attempt + "/" + MAX_ATTEMPTS + " failed for "
                    + task.key.messageId + " (" + attemptFailure.getClass().getSimpleName()
                    + "), retry in " + backoff + "ms");
            if (shutdown || !sleepInterruptibly(backoff)) {
                throw new TranslationException(TranslationException.Kind.CANCELLED,
                        "重试等待被中断", attemptFailure);
            }
        }
        throw last != null ? last
                : new TranslationException(TranslationException.Kind.UNKNOWN, "未知失败");
    }

    private static boolean isRetryable(Throwable t) {
        return t instanceof TranslationException
                && ((TranslationException) t).isRetryable();
    }

    static long backoffMillis(int attempt, Throwable t) {
        long exponential = Math.min(4000L, 1000L * (1L << (attempt - 1)));
        if (t instanceof TranslationException) {
            TranslationException error = (TranslationException) t;
            if (error.httpStatus() == 429) {
                long rateLimitBackoff = Math.min(8000L, 2000L * (1L << (attempt - 1)));
                long retryAfter = error.retryAfterSeconds() * 1000L;
                return Math.min(60000L, Math.max(retryAfter, rateLimitBackoff));
            }
        }
        return exponential;
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
        String hex = Sha256.digestHex(text, configVersion);
        if (hex != null) {
            return hex;
        }
        // 理论不可达；退化为稳定但非加密强度的哈希。
        return Integer.toHexString((text + "|" + configVersion).hashCode());
    }

    /** 主线程投递工具。Discord 消息渲染全部发生在主线程。 */
    public static final class MainThread {
        interface Dispatcher {
            boolean post(Runnable runnable);
        }

        private static final Object LOCK = new Object();
        private static volatile android.os.Handler sHandler;
        private static volatile Dispatcher sTestDispatcher;
        private static long sEpoch;

        private MainThread() {
        }

        public static void install() {
            synchronized (LOCK) {
                if (sHandler == null) {
                    sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                }
                sTestDispatcher = null;
                sEpoch++;
            }
        }

        public static void clear() {
            android.os.Handler old;
            synchronized (LOCK) {
                old = sHandler;
                sHandler = null;
                sTestDispatcher = null;
                sEpoch++;
            }
            if (old != null) {
                old.removeCallbacksAndMessages(null);
            }
        }

        /** 仅供同包 JVM 单测显式启用；生产环境没有 direct-dispatch fallback。 */
        static void setDirectDispatcherForTests() {
            setDispatcherForTests(runnable -> {
                runnable.run();
                return true;
            });
        }

        static void setDispatcherForTests(Dispatcher dispatcher) {
            android.os.Handler old;
            synchronized (LOCK) {
                old = sHandler;
                sHandler = null;
                sTestDispatcher = dispatcher;
                sEpoch++;
            }
            if (old != null) {
                old.removeCallbacksAndMessages(null);
            }
        }

        public static void post(Runnable runnable) {
            final android.os.Handler handler;
            final Dispatcher dispatcher;
            final long epoch;
            synchronized (LOCK) {
                handler = sHandler;
                dispatcher = sTestDispatcher;
                epoch = sEpoch;
            }
            if (handler == null && dispatcher == null) return;

            Runnable guarded = () -> {
                synchronized (LOCK) {
                    if (epoch != sEpoch || (handler != null && handler != sHandler)
                            || (dispatcher != null && dispatcher != sTestDispatcher)) {
                        return;
                    }
                }
                runnable.run();
            };
            if (dispatcher != null) {
                dispatcher.post(guarded);
            } else if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                guarded.run();
            } else {
                handler.post(guarded);
            }
        }
    }
}
