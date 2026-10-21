package com.wxmyyds.discordautotranslate.hooks;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Diagnostics;
import com.wxmyyds.discordautotranslate.core.XLog;

/**
 * 在 Discord 界面内展示诊断日志。
 *
 * <p><b>为什么在宿主内弹窗</b>：用户无法提供 adb/logcat，LSPosed 日志也不易导出；
 * 而跨进程回传已被证伪（Android 11 起 {@code android:forceQueryable} 只对系统应用
 * 生效，普通应用的 Provider 仍被包可见性过滤，见 {@link Diagnostics} 类注释）。
 * 弹窗是唯一不依赖额外权限、又能让用户"全选复制"的通道。</p>
 *
 * <p>本类只在 Discord 进程内被调用，使用宿主的 {@link Context} 构造对话框，
 * 因此不持有任何静态 Activity 引用，也不会泄漏。</p>
 */
final class DiagnosticsDialog {

    private DiagnosticsDialog() {
    }

    /**
     * 弹出诊断日志。
     *
     * @param context 宿主 Context（来自被点击的视图），必须能用于显示窗口
     */
    static void show(@Nullable Context context) {
        if (context == null) {
            XLog.w("diagnostics dialog: no context");
            return;
        }
        final String content = Diagnostics.snapshot(0);
        final String body = content.isEmpty() ? "（暂无诊断记录）" : content;

        // 弹窗必须在主线程构建；图标点击本身来自主线程，这里仍做一次保护，
        // 避免将来从后台线程调用时崩溃（崩溃会连带影响宿主）。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    show(context);
                }
            });
            return;
        }

        try {
            final Context appContext = context.getApplicationContext() == null
                    ? context : context.getApplicationContext();

            TextView text = new TextView(context);
            text.setTextSize(10f);
            // 等宽字体：诊断行含大量数字字段，对齐后才能快速看出 before/after 差异。
            text.setTypeface(android.graphics.Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setText(body);
            int pad = Math.round(12 * context.getResources().getDisplayMetrics().density);
            text.setPadding(pad, pad, pad, pad);

            ScrollView scroll = new ScrollView(context);
            scroll.addView(text);

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("布局诊断（" + (content.isEmpty() ? 0 : countLines(content)) + " 条）")
                    .setView(scroll)
                    .setPositiveButton("复制", (d, which) -> copy(appContext, body))
                    .setNeutralButton("清空", (d, which) -> {
                        Diagnostics.reset();
                        toast(appContext, "诊断已清空");
                    })
                    .setNegativeButton("关闭", null)
                    .create();
            dialog.show();
        } catch (Throwable t) {
            // 弹窗失败绝不能影响宿主，也不能让用户拿不到任何反馈。
            XLog.w("diagnostics dialog failed: " + t.getClass().getSimpleName(), t);
            toast(context, "无法显示诊断：" + t.getClass().getSimpleName());
        }
    }

    private static int countLines(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static void copy(Context context, String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast(context, "剪贴板不可用");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text));
            toast(context, "诊断已复制到剪贴板");
        } catch (Throwable t) {
            toast(context, "复制失败：" + t.getClass().getSimpleName());
        }
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Toast 不可用时忽略。
        }
    }
}
