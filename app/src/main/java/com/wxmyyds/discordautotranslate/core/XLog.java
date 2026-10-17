package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * 统一日志出口。
 *
 * <p>日志通过 libxposed 框架输出（{@link XposedInterface#log(int, String, String)}），
 * 未附加框架时降级到 {@code android.util.Log}。</p>
 *
 * <p>详细日志只增加流程诊断，不改变正文脱敏规则。</p>
 * <p>密钥需要通过 {@link #redact(String)} 显式脱敏后才能记录。</p>
 */
public final class XLog {

    public static final String TAG = "DiscordAutoTranslate";

    private static volatile XposedInterface sXposed;
    private static volatile boolean sVerbose = false;

    private XLog() {
    }

    /** 由模块入口在 {@code onModuleLoaded} 中调用一次。 */
    public static void attach(@Nullable XposedInterface xposed) {
        sXposed = xposed;
    }

    /** 热重载时清理旧引用，避免旧实例被新代次持有。 */
    public static void detach() {
        sXposed = null;
    }

    /** 设置是否输出 DEBUG 级诊断日志。 */
    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static void d(String msg) {
        if (sVerbose) {
            log(3 /* Log.DEBUG */, msg, null);
        }
    }

    public static void i(String msg) {
        log(4 /* Log.INFO */, msg, null);
    }

    public static void w(String msg) {
        log(5 /* Log.WARN */, msg, null);
    }

    public static void w(String msg, Throwable t) {
        log(5 /* Log.WARN */, msg, t);
    }

    public static void e(String msg, Throwable t) {
        log(6 /* Log.ERROR */, msg, t);
    }

    private static void log(int priority, String msg, @Nullable Throwable tr) {
        // 1) 写 logcat。
        //
        // 为什么两者都写：XposedInterface.log() 的输出进了 LSPosed 自己的日志
        // （需在 LSPosed 管理器里查看），adb logcat 默认看不到。
        // 排查阶段必须让 adb logcat 也能看到，否则无法定位问题。
        try {
            if (tr != null) {
                android.util.Log.println(priority, TAG,
                        msg + "\n" + android.util.Log.getStackTraceString(tr));
            } else {
                android.util.Log.println(priority, TAG, msg);
            }
        } catch (Throwable ignored) {
            // logcat 不可用时忽略
        }

        // 2) 同步写框架日志（LSPosed 管理器可见，且与框架日志同处便于对照）。
        XposedInterface x = sXposed;
        if (x != null) {
            try {
                if (tr != null) {
                    x.log(priority, TAG, msg, tr);
                } else {
                    x.log(priority, TAG, msg);
                }
            } catch (Throwable ignored) {
                // 框架日志不可用时忽略
            }
        }
    }

    /**
     * 对疑似密钥做脱敏：只保留首尾少量字符。
     */
    public static String redact(@Nullable String secret) {
        if (secret == null || secret.isEmpty()) {
            return "<empty>";
        }
        int n = secret.length();
        if (n <= 8) {
            return "***(len=" + n + ")";
        }
        return secret.substring(0, 3) + "***(len=" + n + ")";
    }

    /** 用更稳妥的固定长度摘要表示敏感正文，详细日志开关不改变脱敏规则。 */
    public static String text(@Nullable String body) {
        if (body == null) {
            return "<null>";
        }
        // 正文无论 verbose 开关状态都不得写入日志；即使短文本也只记录长度。
        return String.format(Locale.US, "<len=%d>", body.length());
    }
}
