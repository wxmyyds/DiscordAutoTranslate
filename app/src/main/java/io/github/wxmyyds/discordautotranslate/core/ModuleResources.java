package io.github.wxmyyds.discordautotranslate.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.R;

import io.github.libxposed.api.XposedInterface;

/**
 * 模块自身资源的加载器。
 *
 * <p>本模块运行在 Discord 进程内，该进程默认的资源表属于
 * Discord，无法直接访问模块 APK 里的 drawable。必须用模块自己的
 * {@code ApplicationInfo} 取到模块的 {@code Resources} 才能解析这些图标。</p>
 *
 * <p>全部使用公开 API（{@link PackageManager#getResourcesForApplication} /
 * {@link Context#createPackageContext}），不依赖隐藏 API，
 * 避免在较新 Android 上被隐藏 API 限制拦截。</p>
 */
public final class ModuleResources {

    /** 翻译图标（已翻译 / 显示原文）。 */
    private static final String ICON_TRANSLATE = "translate_24";
    /** 翻译中图标（旋转）。 */
    private static final String ICON_PROGRESS = "progress_activity_24";
    /** 失败图标。 */
    private static final String ICON_ERROR = "error_24";

    /**
     * 显式引用 R.drawable，防止开启资源压缩（shrinkResources）时
     * 因代码中只用 {@code getIdentifier} 按名字查而把这些图标误删。
     */
    @SuppressWarnings("unused")
    private static final int[] KEEP_ALIVE = {
            R.drawable.translate_24,
            R.drawable.progress_activity_24,
            R.drawable.error_24,
    };

    private static volatile XposedInterface sXposed;
    /** 解析出的模块资源表；为 null 表示未解析或解析失败。 */
    private static volatile Resources sModuleRes;
    private static volatile String sModulePkg;
    private static volatile boolean sResolved;

    private static volatile Drawable sTranslate;
    private static volatile Drawable sProgress;
    private static volatile Drawable sError;

    private ModuleResources() {
    }

    /** 由模块入口在 {@code onModuleLoaded} 中调用一次。 */
    public static void attach(@Nullable XposedInterface xposed) {
        sXposed = xposed;
    }

    /** 热重载时清理缓存，避免旧代次持有旧 Drawable。 */
    public static void reset() {
        sXposed = null;
        sModuleRes = null;
        sModulePkg = null;
        sResolved = false;
        sTranslate = null;
        sProgress = null;
        sError = null;
    }

    /** 一次性解析模块资源表；成功后缓存，后续调用为纯内存读取。 */
    private static boolean ensureResources(Context ctx) {
        if (sResolved) {
            return sModuleRes != null;
        }
        synchronized (ModuleResources.class) {
            if (sResolved) {
                return sModuleRes != null;
            }
            XposedInterface x = sXposed;
            if (x == null) {
                return false;
            }
            ApplicationInfo info;
            try {
                info = x.getModuleApplicationInfo();
            } catch (Throwable t) {
                return false;
            }
            if (info == null || info.packageName == null) {
                return false;
            }
            sModulePkg = info.packageName;

            // 路径一：直接向 PackageManager 要该应用（模块自身）的资源。
            try {
                Resources res = ctx.getPackageManager().getResourcesForApplication(info);
                if (res != null) {
                    sModuleRes = res;
                    sResolved = true;
                    return true;
                }
            } catch (Throwable ignored) {
                // 路径一失败：继续尝试路径二。
            }

            // 路径二：创建模块的包上下文再取资源（部分 ROM 上路径一不可用）。
            try {
                Context moduleCtx = ctx.createPackageContext(
                        info.packageName, Context.CONTEXT_IGNORE_SECURITY);
                Resources res = moduleCtx == null ? null : moduleCtx.getResources();
                if (res != null) {
                    sModuleRes = res;
                    sResolved = true;
                    return true;
                }
            } catch (Throwable ignored) {
                // 路径二失败：图标留空，不影响宿主。
            }
            return false;
        }
    }

    /** 翻译图标（已翻译 / 显示原文）。未解析成功时返回 null。 */
    @Nullable
    public static Drawable translateIcon(@Nullable Context ctx) {
        Drawable cached = sTranslate;
        if (cached != null || ctx == null) {
            return cached;
        }
        synchronized (ModuleResources.class) {
            if (sTranslate == null && ensureResources(ctx)) {
                sTranslate = resolve(ICON_TRANSLATE);
            }
            return sTranslate;
        }
    }

    /** 翻译中图标（旋转）。未解析成功时返回 null。 */
    @Nullable
    public static Drawable progressIcon(@Nullable Context ctx) {
        Drawable cached = sProgress;
        if (cached != null || ctx == null) {
            return cached;
        }
        synchronized (ModuleResources.class) {
            if (sProgress == null && ensureResources(ctx)) {
                sProgress = resolve(ICON_PROGRESS);
            }
            return sProgress;
        }
    }

    /** 失败图标。未解析成功时返回 null。 */
    @Nullable
    public static Drawable errorIcon(@Nullable Context ctx) {
        Drawable cached = sError;
        if (cached != null || ctx == null) {
            return cached;
        }
        synchronized (ModuleResources.class) {
            if (sError == null && ensureResources(ctx)) {
                sError = resolve(ICON_ERROR);
            }
            return sError;
        }
    }

    @Nullable
    private static Drawable resolve(String name) {
        Resources res = sModuleRes;
        String pkg = sModulePkg;
        if (res == null || pkg == null) {
            return null;
        }
        try {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) {
                return null;
            }
            // 传 null 主题：矢量图内已移除主题属性引用，避免解析失败。
            return res.getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }
}
