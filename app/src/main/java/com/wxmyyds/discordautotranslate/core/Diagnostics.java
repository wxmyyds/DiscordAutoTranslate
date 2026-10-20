package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 布局诊断缓冲区。
 *
 * <p>设备上常常取不到 logcat（无 root、未连 adb），因此诊断结果不能只依赖日志。
 * 本类把最近的诊断行保存在有界缓冲区里，并通过 {@link Sink} 交给调用方落盘——
 * 模块进程写入远程配置，设置页读出来直接显示。</p>
 *
 * <p>只保存流程性数据（消息 ID、视图类型、是否附着、高度、结论标签），
 * 不含消息正文与 API 密钥。</p>
 */
public final class Diagnostics {

    /** 最多保留的诊断行数，避免无限增长。 */
    static final int MAX_LINES = 40;

    /** 落盘出口；未安装时为 null。 */
    public interface Sink {
        /** @param text 当前完整诊断内容（多行） */
        void write(String text);
    }

    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static volatile Sink sSink;

    private Diagnostics() {
    }

    /** 安装或清除落盘出口。 */
    public static void install(@Nullable Sink sink) {
        sSink = sink;
    }

    /**
     * 记录一行诊断并推送给出口。
     *
     * <p>出口异常被静默忽略：诊断功能绝不能影响消息渲染。</p>
     */
    public static void record(@Nullable String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        final String text;
        synchronized (LOCK) {
            if (LINES.size() >= MAX_LINES) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
            text = joinLocked();
        }
        Sink sink = sSink;
        if (sink == null) {
            return;
        }
        try {
            sink.write(text);
        } catch (Throwable ignored) {
            // 诊断写盘失败不影响渲染路径。
        }
    }

    /** 当前诊断内容快照。 */
    public static String snapshot() {
        synchronized (LOCK) {
            return joinLocked();
        }
    }

    /** 清空缓冲区。 */
    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }

    private static String joinLocked() {
        StringBuilder sb = new StringBuilder(LINES.size() * 96);
        for (String line : LINES) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
