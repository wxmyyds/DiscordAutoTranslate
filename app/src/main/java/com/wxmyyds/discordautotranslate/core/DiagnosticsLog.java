package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 读取与格式化 Discord 进程推送的诊断日志文件。
 *
 * <p>文件由 {@link DiagnosticsProvider} 以<b>追加</b>方式写入，每批数据形如：</p>
 * <pre>
 * T|ts=1730000000000 n=3 dropped=0 host=/data/user/0/com.discord at=120s
 * I|layout-diag: msg=...
 * D|row-refresh-after-layout(...)
 * </pre>
 * <p>{@link #format(String, int)} 把批次头转成可读时间、并去掉行前缀，纯函数，
 * 便于单元测试。文件读写失败一律返回空/失败状态，不抛出到 UI。</p>
 */
public final class DiagnosticsLog {

    /** 批次头前缀。 */
    private static final String TAG_BATCH = "T|";

    /** 单行前缀：I=info，D=debug。 */
    private static final char TAG_SEPARATOR = '|';

    /** 一次最多展示的行数（取文件尾部最新内容）。 */
    public static final int MAX_DISPLAY_LINES = 400;

    /** 读取上限：文件本身会被 Provider 轮转，这里再兜一层。 */
    private static final int MAX_READ_BYTES = 512 * 1024;

    private DiagnosticsLog() {
    }

    /** 日志文件位置（与 {@link DiagnosticsProvider} 一致）。 */
    public static File file(@Nullable File filesDir) {
        return filesDir == null ? null : new File(filesDir, DiagnosticsProvider.LOG_FILE_NAME);
    }

    /** 读取原始内容；文件不存在或读取失败时返回空串。 */
    public static String read(@Nullable File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                // 只保留尾部：诊断越新越有价值。
                if (total + read > MAX_READ_BYTES) {
                    byte[] keep = buffer.toByteArray();
                    int drop = keep.length + read - MAX_READ_BYTES;
                    buffer.reset();
                    if (drop < keep.length) {
                        buffer.write(keep, drop, keep.length - drop);
                    }
                }
                buffer.write(chunk, 0, read);
                total += read;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 清空日志（只由设置页在本进程内执行）。 */
    public static boolean clear(@Nullable File file) {
        if (file == null) {
            return false;
        }
        try {
            if (!file.exists()) {
                return true;
            }
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.flush();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 格式化原始日志用于展示。
     *
     * @param raw      文件原始内容
     * @param maxLines 最多保留的行数（从尾部取最新的）
     * @return 可读文本；无内容时返回空串
     */
    public static String format(@Nullable String raw, int maxLines) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] all = raw.split("\n", -1);
        List<String> lines = new ArrayList<>(all.length);
        for (String line : all) {
            if (line.isEmpty()) {
                continue;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return "";
        }
        int from = 0;
        boolean truncated = false;
        if (maxLines > 0 && lines.size() > maxLines) {
            from = lines.size() - maxLines;
            truncated = true;
        }

        StringBuilder sb = new StringBuilder(lines.size() * 64);
        if (truncated) {
            sb.append("… 仅显示最近 ").append(maxLines).append(" 行（共 ")
                    .append(lines.size()).append(" 行）\n\n");
        }
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(TAG_BATCH)) {
                sb.append(batchHeader(line, time)).append('\n');
                continue;
            }
            if (line.length() > 2 && line.charAt(1) == TAG_SEPARATOR) {
                sb.append("  ").append(line.substring(2)).append('\n');
                continue;
            }
            // 旧格式或非预期行：原样保留，避免信息丢失。
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String batchHeader(String line, SimpleDateFormat time) {
        long ts = -1L;
        int count = -1;
        int dropped = -1;
        for (String field : line.split(" ")) {
            if (field.startsWith("ts=")) {
                ts = parseLong(field.substring(3), -1L);
            } else if (field.startsWith("n=")) {
                count = (int) parseLong(field.substring(2), -1L);
            } else if (field.startsWith("dropped=")) {
                dropped = (int) parseLong(field.substring(8), -1L);
            }
        }
        StringBuilder sb = new StringBuilder("──── ");
        sb.append(ts > 0 ? time.format(new Date(ts)) : "时间未知");
        sb.append(" · 推送 ");
        sb.append(count > 0 ? String.valueOf(count) : "?");
        sb.append(" 条");
        if (dropped > 0) {
            sb.append("（队列溢出丢弃 ").append(dropped).append(" 条）");
        }
        return sb.append(" ────").toString();
    }

    private static long parseLong(String value, long def) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
