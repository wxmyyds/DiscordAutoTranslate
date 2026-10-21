package com.wxmyyds.discordautotranslate;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.Diagnostics;
import com.wxmyyds.discordautotranslate.core.ModuleResources;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.TranslationCache;
import com.wxmyyds.discordautotranslate.core.XLog;
import com.wxmyyds.discordautotranslate.hooks.HookBridge;
import com.wxmyyds.discordautotranslate.hooks.InlineIconSpan;
import com.wxmyyds.discordautotranslate.hooks.RerenderRegistry;
import com.wxmyyds.discordautotranslate.hooks.StructurableTextFactory;
import com.wxmyyds.discordautotranslate.hooks.MessageHooks;

import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块唯一入口类。
 *
 * <p>约束（依据 libxposed API 102.0.0 源码）：
 * <ul>
 *   <li>必须 {@code extends XposedModule} 且提供公开无参构造 —— 框架按此实例化。</li>
 *   <li>热重载<b>仅支持恰好一个 Java 入口类</b>。因此所有 Hook 都由本类统一注册，
 *       不允许再有第二个入口。</li>
 *   <li>不应在 {@code onModuleLoaded} 之前做初始化工作。</li>
 *   <li>不需要（也不应）自行调用 {@code attachFramework()}。</li>
 * </ul>
 *
 * <p>入口声明不在 Manifest 中，而在 {@code META-INF/xposed/java_init.list}；
 * 模块元数据在 {@code META-INF/xposed/module.prop}；作用域在
 * {@code META-INF/xposed/scope.list}。</p>
 */
public class ModuleEntry extends XposedModule {

    /** 固定包名，scope.list 已限定，仍做二次校验（框架可能回传同进程内的其他包）。 */
    private static final String TARGET_PACKAGE = "com.discord";

    /** 已确认适配的 Discord 版本（smali/com/discord/BuildConfig.smali）。 */
    private static final String ADAPTED_VERSION_NAME = "340.13";
    private static final int ADAPTED_VERSION_CODE = 340013;

    /** 当前代次持有的 hook 句柄，热重载时用于清理。 */
    private final List<XposedInterface.HookHandle> hooks = new java.util.ArrayList<>();

    private volatile boolean installed;
    private volatile ClassLoader targetClassLoader;

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        XLog.attach(this);
        ModuleResources.attach(this);
        XLog.i("onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
        // 目标进程才需要初始化；其他进程立即停止接收回调，减少无谓开销。
        if (param.isSystemServer()) {
            detach();
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            XLog.d("skip package " + param.getPackageName());
            return;
        }
        if (installed) {
            // 防止重复安装（需求 §风险 6：不得重复注册 Hook）。
            XLog.w("already installed, skip duplicate onPackageReady");
            return;
        }
        targetClassLoader = param.getClassLoader();

        try {
            install(param.getClassLoader());
            installed = true;
        } catch (Throwable t) {
            // Hook 失败不能导致 Discord 崩溃（需求 §5.1）。仅记录诊断信息。
            installed = false;
            XLog.e("install hooks failed, module inactive in this process", t);
        }
    }

    /**
     * 热重载：在<b>旧</b>代码中执行。返回 true 前必须停止本模块拥有的线程、
     * 释放引用，否则旧代次会被新代次长期持有（需求 §5.1、§风险 6）。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        XLog.i("onHotReloading: releasing old generation resources");
        try {
            release();
            return true;
        } catch (Throwable t) {
            XLog.e("onHotReloading failed, reject reload", t);
            return false;
        }
    }

    /**
     * 热重载：在<b>新</b>代码中执行。包生命周期回调不会被自动重放，
     * 因此必须在这里主动重建状态并重新注册 Hook。
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        XLog.attach(this);
        XLog.i("onHotReloaded: rebuilding generation, oldHooks="
                + param.getOldHookHandles().size());

        // 先移除框架记录的旧 Hook（默认实现即如此，这里显式写出以便诊断）。
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
            try {
                h.unhook();
            } catch (Throwable t) {
                XLog.w("unhook old hook failed: " + h.getId(), t);
            }
        }

        // 热重载不会重放 onPackageReady，因此必须从旧 Hook 目标反推宿主 ClassLoader，
        // 否则新代次无法重新安装 Hook（旧实例的 targetClassLoader 已经不存在）。
        ClassLoader recovered = targetClassLoader;
        if (recovered == null) {
            recovered = classLoaderFromOldHooks(param.getOldHookHandles());
        }
        if (recovered != null) {
            targetClassLoader = recovered;
        }

        // Drop old state and the old thread pool so stale results cannot leak in.
        Scheduler.MainThread.clear();
        Scheduler.reset();
        StateStore.get().onNewGeneration();
        HookBridge.get().reset();
        TranslationCache.reset();
        RerenderRegistry.clear();
        StructurableTextFactory.reset();
        // 图标 Drawable 缓存在模块资源加载器里，热重载后必须重新解析：
        // 否则旧代次持有的 Drawable 会被继续使用。
        InlineIconSpan.resetAll();
        ModuleResources.reset();
        // reset() 会把框架引用置空；必须重新绑定，否则图标资源全部解析失败。
        ModuleResources.attach(this);

        hooks.clear();
        installed = false;

        // 重新读取配置（需求 §5.1：热重载后必须重新读取有效配置）。
        reloadConfig();

        // 重新安装 Hook。
        ClassLoader cl = targetClassLoader;
        if (cl == null) {
            XLog.w("no cached classloader after hot reload; will install on next onPackageReady");
            return;
        }
        try {
            install(cl);
            installed = true;
            XLog.i("hot reload rebuild finished");
        } catch (Throwable t) {
            XLog.e("reinstall after hot reload failed, module inactive until restart", t);
        }
    }

    // ------------------------------------------------------------ 安装与卸载

    private void install(ClassLoader cl) {
        // 记录目标 Discord 版本，便于诊断（需求 §9.1）。
        logTargetVersion(cl);

        StructurableTextFactory.init(cl);
        Scheduler.MainThread.install();
        reloadConfig();
        // 行内图标所需的 Drawable 由 ModuleResources 按需解析（内部已缓存），
        // 不再需要在安装阶段预加载到某个视图类。

        List<XposedInterface.HookHandle> created = MessageHooks.install(this, cl);
        if (created.size() < MessageHooks.MIN_REQUIRED_HOOKS) {
            // 半安装状态会让模块“看似已启用、实际不工作”；回滚并等待重启。
            XLog.e("only " + created.size() + " hooks installed; rolling back partial install",
                    null);
            for (XposedInterface.HookHandle handle : created) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // 回滚尽力而为。
                }
            }
            throw new IllegalStateException("incomplete hook installation: "
                    + created.size() + "/" + MessageHooks.MIN_REQUIRED_HOOKS);
        }
        hooks.addAll(created);
        XLog.i("installed " + created.size() + " hooks");
    }

    /** 从旧 Hook 的目标方法反推宿主 ClassLoader（热重载后包回调不会重放）。 */
    @Nullable
    private static ClassLoader classLoaderFromOldHooks(
            @NonNull List<XposedInterface.HookHandle> oldHandles) {
        for (XposedInterface.HookHandle handle : oldHandles) {
            try {
                java.lang.reflect.Executable target = handle.getExecutable();
                Class<?> declaring = target == null ? null : target.getDeclaringClass();
                ClassLoader loader = declaring == null ? null : declaring.getClassLoader();
                if (loader != null) {
                    XLog.i("recovered target classloader from old hook " + handle.getId());
                    return loader;
                }
            } catch (Throwable t) {
                XLog.w("cannot recover classloader from old hook", t);
            }
        }
        return null;
    }

    /** 停止线程、解绑 Hook、清理引用。 */
    private void release() {
        for (XposedInterface.HookHandle h : hooks) {
            try {
                h.unhook();
            } catch (Throwable t) {
                XLog.w("unhook failed: " + h.getId(), t);
            }
        }
        hooks.clear();
        Scheduler.MainThread.clear();
        // 等待线程真正结束再返回 true，否则旧代次线程会继续存活（需求 §5.1）。
        boolean stopped = Scheduler.resetAndAwait(2000L);
        if (!stopped) {
            XLog.w("scheduler threads did not stop within 2s; hot reload continues");
        }
        HookBridge.get().reset();
        TranslationCache.reset();
        RerenderRegistry.clear();
        ModuleResources.reset();
        XLog.detach();
        installed = false;
    }

    private void reloadConfig() {
        try {
            final SharedPreferences prefs = getRemotePreferences(Config.GROUP);
            Config.Snapshot snapshot = Config.read(prefs);
            // 没有 logcat 时诊断只能靠回写：把结果写进远程配置，由设置页显示。
            Diagnostics.install(text -> {
                try {
                    // 必须用 commit()：apply() 是异步落盘，目标进程被切到后台或
                    // 被杀死时这次写入可能根本不会执行，设置页读到的就是空值。
                    prefs.edit().putString(Config.KEY_DIAGNOSTICS, text).commit();
                } catch (Throwable ignored) {
                    // 诊断写盘失败不影响渲染。
                }
            });
            HookBridge.get().updateConfig(snapshot);
            XLog.i("config loaded: enabled=" + snapshot.enabled
                    + " url=" + XLog.safeUrl(snapshot.apiUrl)
                    + " key=" + XLog.redact(snapshot.apiKey));
        } catch (Throwable t) {
            XLog.w("getRemotePreferences failed, using defaults", t);
        }
    }

    private void logTargetVersion(ClassLoader cl) {
        try {
            Class<?> bc = Class.forName("com.discord.BuildConfig", false, cl);
            Object name = bc.getField("VERSION_NAME").get(null);
            Object code = bc.getField("VERSION_CODE").get(null);
            XLog.i("target Discord version=" + name + " code=" + code);
            if (code instanceof Integer && (Integer) code != ADAPTED_VERSION_CODE) {
                XLog.w("Discord version differs from adapted " + ADAPTED_VERSION_NAME
                        + " (" + ADAPTED_VERSION_CODE + "); hooks may not match");
            }
        } catch (Throwable t) {
            XLog.w("cannot read Discord BuildConfig", t);
        }
    }
}
