package com.wxmyyds.discordautotranslate.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.BuildConfig;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 布局诊断日志出口。
 *
 * <p>本类在两个进程里都会存在，行为不同：</p>
 * <ul>
 *   <li><b>Discord 进程（模块 Hook 运行处）</b>：先写 {@link XLog}（logcat + 框架日志），
 *       再把诊断<b>批量</b>推送到模块 App 的 {@link DiagnosticsProvider}，
 *       使设置页可以查看并复制。设备上既没有 adb，LSPosed 日志也不易导出，
 *       这是目前唯一能让用户把诊断取出来的通道。</li>
 *   <li><b>模块 App 进程</b>：只写 {@link XLog}，不推送（推送目标就是本进程自己的 Provider）。</li>
 * </ul>
 *
 * <p><b>为什么必须批量</b>：一次渲染可能产生多条诊断，逐条同步写 Provider 会让
 * Discord 主线程反复做 binder 调用。因此日志先入内存队列，最多每
 * {@link #FLUSH_INTERVAL_MS} 合并成一次写入，队列满时丢弃最旧的并在批末报告计数。</p>
 *
 * <p><b>隐私</b>：调用方（{@code HookBridge}）保证只写入消息 ID、视图类名、高度、
 * 布尔状态与结论标签，不含消息正文与密钥。队列只保留在内存中，进程结束后消失。</p>
 */
public final class Diagnostics {

    /** debug 构建保留更长的队列与更完整的轨迹；release 只保留摘要。 */
    private static final boolean DEBUG = BuildConfig.DEBUG;

    /** 单批最多保留的行数（超出丢弃最旧的）。 */
    private static final int MAX_LINES = 200;

    /** debug 构建放宽队列，便于一次拿全完整轨迹。 */
    private static final int MAX_LINES_DEBUG = 400;

    /** 合并写入的最小间隔；同时限制 Provider 写入频率。 */
    private static final long FLUSH_INTERVAL_MS = 1000L;

    /** 等待推送的行；只在 record()/flush() 的同步块内访问。 */
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();

    private static int sDropped;
    /** 是否已有待执行的合并推送任务。 */
    private static boolean sFlushScheduled;

    /**
     * 推送线程。写 Provider 是 binder + 文件 IO，绝不能在主线程做：
     * 渲染路径本身就跑在 Discord 主线程上。
     */
    private static volatile ExecutorService sWorker;

    /** 宿主 ContentResolver（进程单例语义，不会固定 Activity）。 */
    private static volatile ContentResolver sResolver;
    /** 宿主 dataDir，仅用于在日志里标识写入方。 */
    private static volatile String sHostDataDir;
    /** 推送失败只报告一次，避免失败通道自身刷屏。 */
    private static volatile boolean sPushFailureReported;

    /**
     * 是否启用跨进程回传。默认关闭，由配置在目标进程里显式打开。
     *
     * <p>关闭时 {@link #record} 只写日志，不排队也不推送给模块 App。</p>
     */
    private static volatile boolean sForwardEnabled;

    private static volatile Handler sHandler;

    private Diagnostics() {
    }

    /**
     * 记录一条诊断。
     *
     * <p>无论是否推送成功，都先写 {@link XLog}：logcat/框架日志仍是最终依据，
     * 应用内通道只是为了让用户能把内容复制出来。</p>
     */
    public static void record(@Nullable String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        XLog.i(line);
        if (!sForwardEnabled || !XLog.hasFramework()) {
            // 模块 App 进程没有可推送的目标；开关关闭时也不做跨进程尝试。
            return;
        }
        if (enqueue(line)) {
            scheduleFlush();
        }
    }

    /**
     * 由 Hook 侧的视图事件调用一次，缓存宿主 {@link ContentResolver}。
     *
     * <p>Discord 进程里模块自身没有 Context，只能从宿主视图取；缓存的是
     * process 级单例，不会固定 Activity。</p>
     */
    public static void captureHostContext(@Nullable Context ctx) {
        if (ctx == null || sResolver != null) {
            return;
        }
        try {
            ContentResolver resolver = ctx.getContentResolver();
            if (resolver == null) {
                return;
            }
            sResolver = resolver;
            ApplicationInfo info = ctx.getApplicationInfo();
            if (info != null) {
                sHostDataDir = info.dataDir;
            }
        } catch (Throwable ignored) {
            // 取不到就保持为 null，后续会走“未捕获宿主 Context”分支。
            return;
        }
        // 拿到 Resolver 前产生的诊断被放回了队列，此时必须主动补推一次，
        // 否则这些行要等到下一条新诊断到达才会被送出。
        boolean pending;
        synchronized (QUEUE) {
            pending = !QUEUE.isEmpty();
        }
        if (pending) {
            scheduleFlush();
        }
    }

    /**
     * 设置是否启用跨进程回传。
     *
     * <p>由 {@code HookBridge.updateConfig} 在目标进程里按配置调用。关闭时清空队列，
     * 避免关闭后仍继续推送旧内容。</p>
     */
    public static void setForwardEnabled(boolean enabled) {
        if (sForwardEnabled == enabled) {
            return;
        }
        sForwardEnabled = enabled;
        if (!enabled) {
            synchronized (QUEUE) {
                QUEUE.clear();
                sDropped = 0;
            }
        }
    }

    /** 热重载时丢弃队列、推送线程与宿主引用，避免旧代次残留。 */
    public static void reset() {
        synchronized (QUEUE) {
            QUEUE.clear();
            sDropped = 0;
            sFlushScheduled = false;
        }
        ExecutorService worker = sWorker;
        sWorker = null;
        if (worker != null) {
            worker.shutdownNow();
        }
        sResolver = null;
        sHostDataDir = null;
        sPushFailureReported = false;
    }

    /**
     * 立即推送已排队的日志。
     *
     * <p><b>调用方必须保证不在主线程调用</b>：这个方法直接做 binder + 文件 IO。
     * 正常流程不需要调用它（推送由 {@link #scheduleFlush()} 自动安排）。</p>
     */
    public static void flushPending() {
        flush();
    }

    // ---------------------------------------------------------------- 队列

    /** @return true 表示队列由空变非空，需要安排一次定时推送。 */
    private static boolean enqueue(String line) {
        // 不在这里做去重：诊断的价值就在于"同一结论出现了几次"，
        // 合并会把判读依据（before/after 高度、行数）一并丢掉。
        String entry = (DEBUG ? "D|" : "I|") + line;
        synchronized (QUEUE) {
            boolean wasEmpty = QUEUE.isEmpty();
            int limit = DEBUG ? MAX_LINES_DEBUG : MAX_LINES;
            while (QUEUE.size() >= limit) {
                QUEUE.removeFirst();
                sDropped++;
            }
            QUEUE.addLast(entry);
            return wasEmpty;
        }
    }

    /**
     * 安排一次合并推送。
     *
     * <p>不逐条写：一次渲染会连续产生多条诊断，每条都做 binder 调用会拖慢
     * Discord 主线程。这里合并到下一次延迟任务，最多每 {@link #FLUSH_INTERVAL_MS}
     * 写一次。</p>
     */
    private static void scheduleFlush() {
        synchronized (QUEUE) {
            if (sFlushScheduled) {
                return;
            }
            sFlushScheduled = true;
        }
        try {
            handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (QUEUE) {
                        sFlushScheduled = false;
                    }
                    submitFlush();
                }
            }, FLUSH_INTERVAL_MS);
        } catch (Throwable t) {
            synchronized (QUEUE) {
                sFlushScheduled = false;
            }
        }
    }

    private static void submitFlush() {
        try {
            worker().execute(new Runnable() {
                @Override
                public void run() {
                    flush();
                }
            });
        } catch (Throwable t) {
            // 线程池已关闭（热重载）：队列会在新代次里重新累积。
        }
    }

    private static ExecutorService worker() {
        ExecutorService w = sWorker;
        if (w == null) {
            synchronized (Diagnostics.class) {
                if (sWorker == null) {
                    sWorker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "diagnostics-push");
                            t.setDaemon(true);
                            return t;
                        }
                    });
                }
                w = sWorker;
            }
        }
        return w;
    }

    private static Handler handler() {
        Handler h = sHandler;
        if (h == null) {
            synchronized (Diagnostics.class) {
                if (sHandler == null) {
                    sHandler = new Handler(Looper.getMainLooper());
                }
                h = sHandler;
            }
        }
        return h;
    }

    // ---------------------------------------------------------------- 推送

    private static void flush() {
        List<String> batch;
        int dropped;
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(QUEUE);
            QUEUE.clear();
            dropped = sDropped;
            sDropped = 0;
        }

        StringBuilder sb = new StringBuilder(batch.size() * 96 + 128);
        // 批次头放在最前：设置页按时间顺序展示，时间戳需与内容同批写入。
        sb.append("T|ts=").append(System.currentTimeMillis())
                .append(" n=").append(batch.size())
                .append(" dropped=").append(dropped)
                .append(" host=").append(sHostDataDir == null ? "?" : sHostDataDir)
                .append(" at=").append(SystemClock.elapsedRealtime() / 1000L).append('s')
                .append('\n');
        for (String entry : batch) {
            sb.append(entry).append('\n');
        }

        ContentResolver resolver = sResolver;
        if (resolver == null) {
            // 宿主还没有产生任何视图事件，拿不到 Resolver：把内容放回队列，
            // 等 {@link #captureHostContext} 成功后再推。诊断本身已在 logcat 里。
            requeue(batch, dropped);
            return;
        }
        try {
            OutputStream out = resolver.openOutputStream(
                    Uri.parse("content://" + DiagnosticsProvider.AUTHORITY + "/log"));
            if (out == null) {
                throw new IllegalStateException("openOutputStream returned null");
            }
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            sPushFailureReported = false;
        } catch (Throwable t) {
            // 推送失败不重试：失败的往往是权限或 Provider 缺失，重试只会刷屏。
            if (!sPushFailureReported) {
                sPushFailureReported = true;
                XLog.w("diagnostic push failed: " + t.getClass().getSimpleName()
                        + "（应用内日志通道不可用，诊断仍在 logcat/框架日志中）", t);
            }
        }
    }

    /** 把一批内容放回队首（保持时间顺序），超出上限时丢弃最旧的。 */
    private static void requeue(List<String> batch, int dropped) {
        synchronized (QUEUE) {
            int limit = DEBUG ? MAX_LINES_DEBUG : MAX_LINES;
            for (int i = batch.size() - 1; i >= 0; i--) {
                QUEUE.addFirst(batch.get(i));
            }
            sDropped += dropped;
            while (QUEUE.size() > limit) {
                QUEUE.removeFirst();
                sDropped++;
            }
        }
    }
}
