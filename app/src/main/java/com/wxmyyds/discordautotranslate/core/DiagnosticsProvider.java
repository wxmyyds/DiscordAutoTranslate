package com.wxmyyds.discordautotranslate.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 接收 Discord 进程推送的布局诊断。
 *
 * <p><b>为什么需要它</b>：诊断产生于 Discord 进程（模块 Hook 运行处），而用户要
 * 在模块 App 的设置页查看。libxposed 的远程配置在目标进程内是<b>只读</b>的
 * （返回的 {@code SharedPreferences#edit()} 直接抛 {@code UnsupportedOperationException}），
 * 模块私有目录又因跨 UID 写入被 SELinux 拒绝（已实测失败）。因此只能由模块 App
 * 提供一个导出组件接收写入。</p>
 *
 * <p><b>权限设计</b>（导出的 Provider 必须有校验，否则任意应用都能读写该文件）：</p>
 * <ul>
 *   <li>写入（{@code openFile} 写模式、{@code insert}）：
 *       仅允许模块 App 自身 UID 与 {@link #TARGET_PACKAGE} 的 UID。</li>
 *   <li>读取（{@code query}、只读 {@code openFile}）：仅允许模块 App 自身 UID，
 *       避免第三方应用导出本模块的诊断内容。</li>
 * </ul>
 *
 * <p>写入是<b>仅追加</b>的：目标进程只负责往日志文件尾部追加，不提供任何删除或
 * 覆盖路径；「清空」只由设置页在本进程内直接执行。</p>
 *
 * <p><b>隐私</b>：本类不做任何内容解析，只按字节追加；内容安全由
 * {@link Diagnostics} 的记录点负责（不含消息正文与密钥）。</p>
 */
public class DiagnosticsProvider extends ContentProvider {

    /** 与 manifest 中 {@code android:authorities} 必须一致的授权名。 */
    public static final String AUTHORITY = "com.wxmyyds.discordautotranslate.diagnostics";

    /** 允许写入的唯一外部包。 */
    public static final String TARGET_PACKAGE = "com.discord";

    /** 日志文件的相对路径（相对于模块 App 自己的 dataDir）。 */
    public static final String LOG_FILE_NAME = "diagnostics.log";

    /** 超过该大小即轮转为 {@code diagnostics.log.1}，避免单文件无限增长。 */
    private static final long MAX_BYTES = 256L * 1024L;

    /** 只接受这一个 URI；多余路径一律拒绝，避免被当成通用文件写入器。 */
    private static final String PATH_LOG = "log";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        // 读取只允许模块 App 自身：query 不支持跨应用读取诊断内容。
        requireSelf();
        throw new UnsupportedOperationException("query is not supported; read the log file");
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "text/plain";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        // 与 openFile 写模式同一套校验；本 Provider 不使用 insert 路径。
        requireWriter();
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        // 目标进程不允许删除日志；清空只由设置页在本进程内完成。
        requireSelf();
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        requireSelf();
        return 0;
    }

    /**
     * 文件访问入口。
     *
     * <p>写模式一律以 {@code MODE_APPEND} 打开：目标进程只能追加，不能截断或覆盖，
     * 避免一次失败的写入清空已有诊断。“清空”只由设置页在本进程内直接做。</p>
     */
    @Nullable
    @Override
    public android.os.ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws java.io.FileNotFoundException {
        if (!isLogUri(uri)) {
            throw new java.io.FileNotFoundException("unknown path: " + uri);
        }
        if (isWriteMode(mode)) {
            requireWriter();
            File file = logFile(requireContext());
            rotateIfNeeded(file);
            return android.os.ParcelFileDescriptor.open(file,
                    android.os.ParcelFileDescriptor.MODE_CREATE
                            | android.os.ParcelFileDescriptor.MODE_APPEND
                            | android.os.ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        requireSelf();
        return android.os.ParcelFileDescriptor.open(logFile(requireContext()),
                android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static boolean isWriteMode(String mode) {
        return mode != null
                && (mode.contains("w") || mode.contains("a") || mode.contains("t"));
    }

    private android.content.Context requireContext() {
        android.content.Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("provider not attached");
        }
        return ctx;
    }

    // ---------------------------------------------------------------- 文件

    /** 日志文件：模块 App 私有目录下的固定文件名。 */
    @NonNull
    public static File logFile(@NonNull android.content.Context ctx) {
        File dir = ctx.getFilesDir();
        if (dir == null) {
            throw new IllegalStateException("filesDir unavailable");
        }
        return new File(dir, LOG_FILE_NAME);
    }

    /**
     * 超过上限时轮转为 {@code diagnostics.log.1}（覆盖旧备份）。
     *
     * <p>轮转失败不影响本次追加：极端情况下会清空当前文件，保证体积不会无限增长。</p>
     */
    private static void rotateIfNeeded(File file) {
        try {
            if (!file.isFile() || file.length() <= MAX_BYTES) {
                return;
            }
            File backup = new File(file.getParentFile(), LOG_FILE_NAME + ".1");
            if (backup.exists() && !backup.delete()) {
                new FileOutputStream(file, false).close();
                return;
            }
            if (!file.renameTo(backup)) {
                new FileOutputStream(file, false).close();
            }
        } catch (Throwable ignored) {
            // 轮转失败不影响正常追加。
        }
    }

    // ---------------------------------------------------------------- 权限

    private static boolean isLogUri(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority())
                && PATH_LOG.equals(uri.getLastPathSegment());
    }

    /** 允许模块 App 自身与 {@link #TARGET_PACKAGE} 的 UID 写入。 */
    private void requireWriter() {
        int calling = Binder.getCallingUid();
        if (calling == Process.myUid() || calling == targetUid()) {
            return;
        }
        throw new SecurityException("write denied for uid " + calling);
    }

    /** 只允许模块 App 自身 UID 读取。 */
    private void requireSelf() {
        int calling = Binder.getCallingUid();
        if (calling == Process.myUid()) {
            return;
        }
        throw new SecurityException("read denied for uid " + calling);
    }

    /** 目标应用 UID；解析失败时返回 -1（此时只有模块自身可写）。 */
    private int targetUid() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) {
                return -1;
            }
            return ctx.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
