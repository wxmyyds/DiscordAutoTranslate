package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 隐私安全的布局诊断：内存环形缓冲 + 日志输出。
 *
 * <p><b>为什么需要内存缓冲</b>：诊断产生在 Discord 进程里，而用户无法提供
 * adb/logcat，LSPosed 日志也不易导出。把最近若干条诊断留在内存中，
 * 由界面（长按行内翻译图标）弹窗展示，用户就能直接复制走。</p>
 *
 * <p><b>为什么不跨进程</b>：曾尝试用模块 App 的导出 ContentProvider 接收写入，
 * 已被证伪 —— Android 11 起 {@code <application android:forceQueryable="true">}
 * 只对系统应用生效（AOSP 提交 9fdc1bf，标题 "Only allow system apps to be
 * forceQueryable"），普通应用仍被包可见性过滤，Discord 侧
 * {@code openOutputStream} 直接抛 "Unknown URL"。因此在 Discord 进程内呈现
 * 是唯一不依赖额外权限的通道。</p>
 *
 * <p><b>隐私</b>：调用方只写入消息 ID、视图类名、高度/行数与布尔状态，
 * 不含消息正文、译文、密钥或请求头。</p>
 */
public final class Diagnostics {

    /**
     * 缓冲区容量。
     *
     * <p>布局诊断会在每帧渲染路径上产生，容量太大会长期占用内存，
     * 太小又会丢掉判读所需的前后对照。200 条足够覆盖一次完整的
     * "翻译 → 重渲染 → 行刷新 → 布局" 轨迹。</p>
     */
    private static final int MAX_LINES = 200;

    /** 等待展示的行；只在同步块内访问。 */
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    /** 因超出容量被丢弃的行数，展示时提示用户记录不完整。 */
    private static int sDropped;

    private Diagnostics() {
    }

    /** 记录一条诊断：写日志，同时进入内存缓冲。 */
    public static void record(@Nullable String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        XLog.i(line);
        synchronized (LINES) {
            while (LINES.size() >= MAX_LINES) {
                LINES.removeFirst();
                sDropped++;
            }
            LINES.addLast(line);
        }
    }

    /**
     * 取当前缓冲内容快照（按时间顺序）。
     *
     * @param maxLines 最多返回的尾部行数；<=0 表示全部
     * @return 可读文本；无记录时返回空串
     */
    public static String snapshot(int maxLines) {
        List<String> copy;
        int dropped;
        synchronized (LINES) {
            copy = new ArrayList<>(LINES);
            dropped = sDropped;
        }
        if (copy.isEmpty()) {
            return "";
        }
        int from = 0;
        boolean truncated = false;
        if (maxLines > 0 && copy.size() > maxLines) {
            from = copy.size() - maxLines;
            truncated = true;
        }
        StringBuilder sb = new StringBuilder(copy.size() * 64);
        if (dropped > 0 || truncated) {
            sb.append("（共 ").append(copy.size()).append(" 条");
            if (truncated) {
                sb.append("，仅显示最近 ").append(maxLines).append(" 条");
            }
            if (dropped > 0) {
                sb.append("，早期 ").append(dropped).append(" 条已丢弃");
            }
            sb.append("）\n\n");
        }
        for (int i = from; i < copy.size(); i++) {
            sb.append(copy.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 清空缓冲（热重载与新代次开始时调用，避免旧代次数据混淆判读）。 */
    public static void reset() {
        synchronized (LINES) {
            LINES.clear();
            sDropped = 0;
        }
    }
}
