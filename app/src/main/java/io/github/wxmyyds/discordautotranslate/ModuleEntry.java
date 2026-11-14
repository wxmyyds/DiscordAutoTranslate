package io.github.wxmyyds.discordautotranslate;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.HookConfigurationLoader;
import io.github.wxmyyds.discordautotranslate.core.ModuleResources;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.hooks.EmbedHooks;
import io.github.wxmyyds.discordautotranslate.hooks.HookBridge;
import io.github.wxmyyds.discordautotranslate.hooks.InlineIconSpan;
import io.github.wxmyyds.discordautotranslate.hooks.RerenderRegistry;
import io.github.wxmyyds.discordautotranslate.hooks.StructurableTextFactory;
import io.github.wxmyyds.discordautotranslate.hooks.MessageHooks;
import io.github.wxmyyds.discordautotranslate.hooks.PollHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块唯一入口类。
 *
 * <p>约束（依据 libxposed API 102.0.0 源码）：
 * <ul>
 *   <li>必须 {@code extends XposedModule} 且提供公开无参构造，框架按此实例化。</li>
 *   <li>热重载仅支持恰好一个 Java 入口类。因此所有 Hook 都由本类统一注册，
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

    /** 当前代次持有的 hook 句柄，热重载时用于清理。 */
    private final List<XposedInterface.HookHandle> hooks = new java.util.ArrayList<>();

    private volatile boolean installed;
    private volatile boolean bootstrapInstalled;
    private volatile HookConfiguration hookConfiguration;

    private void diagnosticInfo(String message) {
        android.util.Log.i("DiscordAutoTranslate", message);
        try {
            log(android.util.Log.INFO, "DiscordAutoTranslate", message);
        } catch (Throwable ignored) {
            // The framework log is supplemental; Android log remains available.
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        diagnosticInfo("onModuleLoaded lifecycle callback received");
        ModuleResources.attach(this);
        // 目标进程才需要初始化；其他进程立即停止接收回调，减少无谓开销。
        if (param.isSystemServer()) {
            detach();
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        diagnosticInfo("onPackageReady received for Discord");
        if (installed || bootstrapInstalled) {
            // 防止重复注册启动钩子。
            return;
        }
        installBootstrapHook();
    }

    /**
     * 热重载：在旧代码中执行。返回 true 前必须停止本模块拥有的线程、
     * 释放引用，否则旧代次会被新代次长期持有（需求 §5.1、§风险 6）。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        try {
            Bundle state = new Bundle();
            if (hookConfiguration != null) {
                state.putInt("hookVersionCode", hookConfiguration.versionCode());
            }
            param.setSavedInstanceState(state);
            release();
            return true;
        } catch (Throwable ignored) {
            // 释放失败：拒绝本次热重载，让框架保留旧代次。
            return false;
        }
    }

    /**
     * 热重载：在新代码中执行。包生命周期回调不会被自动重放，
     * 因此必须在这里主动重建状态并重新注册 Hook。
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        // 先移除框架记录的旧 Hook（默认实现即如此，这里显式执行以免句柄泄漏）。
        for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
            try {
                h.unhook();
            } catch (Throwable ignored) {
                // 单个 unhook 失败不影响其余清理。
            }
        }

        // 宿主 loader 只从已保存 profile 的绑定 Hook 取得，排除 Application.attach。

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

        Object savedState = param.getSavedInstanceState();
        HookConfiguration restored = null;
        int restoredVersionCode = -1;
        if (savedState instanceof Bundle) {
            Bundle bundle = (Bundle) savedState;
            restoredVersionCode = bundle.getInt("hookVersionCode", -1);
            if (restoredVersionCode >= 0) {
                try {
                    // Reload from the new APK, never reactivate the old generation's
                    // serialized profile after selectors/constructor metadata changed.
                    restored = HookConfigurationLoader.load(
                            getModuleApplicationInfo().sourceDir, restoredVersionCode);
                } catch (Throwable t) {
                    diagnosticInfo("hot reload profile restoration failed error="
                            + t.getClass().getSimpleName());
                }
            }
        }

        hooks.clear();
        installed = false;
        bootstrapInstalled = false;
        hookConfiguration = null;
        reloadConfig();

        ClassLoader recovered = restored == null ? null
                : classLoaderFromOldHooks(param.getOldHookHandles(), restored);
        if (restored != null && recovered != null) {
            try {
                install(recovered, restored);
                installed = true;
            } catch (Throwable t) {
                installed = false;
                diagnosticInfo("hot reload hook installation failed error="
                        + t.getClass().getSimpleName() + "; restart Discord required");
            }
        } else {
            diagnosticInfo("hot reload restoration unavailable; restart Discord required");
        }
    }

    // ------------------------------------------------------------ 安装与卸载

    private void installBootstrapHook() {
        if (bootstrapInstalled) {
            return;
        }
        try {
            java.lang.reflect.Method attach = Application.class.getDeclaredMethod(
                    "attach", Context.class);
            attach.setAccessible(true);
            XposedInterface.HookHandle handle = hook(attach)
                    .setId("dat.applicationAttach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object contextObject = chain.getArg(0);
                            if (contextObject instanceof Context) {
                                Context context = (Context) contextObject;
                                if (TARGET_PACKAGE.equals(context.getPackageName())) {
                                    diagnosticInfo("Application.attach reached for Discord");
                                    installForApplication(context, context.getClassLoader());
                                }
                            }
                        } catch (Throwable t) {
                            android.util.Log.e("DiscordAutoTranslate",
                                    "failed to select Discord hook profile", t);
                        }
                        return result;
                    });
            hooks.add(handle);
            bootstrapInstalled = true;
            diagnosticInfo("Application.attach bootstrap hook installed");
        } catch (Throwable t) {
            android.util.Log.e("DiscordAutoTranslate", "failed to install app bootstrap hook", t);
            installed = false;
        }
    }

    private void installForApplication(Context context, ClassLoader cl) {
        if (installed) {
            return;
        }
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    TARGET_PACKAGE, 0);
            int versionCode = packageInfo.versionCode;
            diagnosticInfo("selecting hook profile for versionCode=" + versionCode);
            HookConfiguration config = HookConfigurationLoader.load(
                    getModuleApplicationInfo().sourceDir, versionCode);
            if (config == null) {
                android.util.Log.w("DiscordAutoTranslate",
                        "no hook profile for Discord versionCode=" + versionCode);
                return;
            }
            install(cl, config);
            installed = true;
        } catch (Throwable t) {
            android.util.Log.e("DiscordAutoTranslate", "failed to install configured hooks", t);
            installed = false;
        }
    }

    private void install(ClassLoader cl, HookConfiguration config) {
        if (!config.hasAllRequiredTargets(cl)) {
            throw new IllegalStateException("configured required hook target is unavailable");
        }
        hookConfiguration = config;
        HookConfiguration.activate(config);
        StructurableTextFactory.init(cl, config);
        Scheduler.MainThread.install();
        reloadConfig();

        Set<String> installedHookIds = new HashSet<>();
        List<XposedInterface.HookHandle> created = new ArrayList<>();
        try {
            created.addAll(MessageHooks.install(this, cl, config, installedHookIds));
            created.addAll(EmbedHooks.install(this, cl, config, installedHookIds));
            created.addAll(PollHooks.install(this, cl, config, installedHookIds));
            if (!config.hasAllRequiredHooks(installedHookIds)) {
                throw new IllegalStateException("incomplete configured hook installation");
            }
        } catch (Throwable failure) {
            for (XposedInterface.HookHandle handle : created) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                    // Roll back incomplete required hook installation.
                }
            }
            throw new IllegalStateException("configured hook installation failed", failure);
        }
        hooks.addAll(created);
        diagnosticInfo("installed hook profile versionCode=" + config.versionCode()
                + " requiredHookIds=" + installedHookIds);
    }

    /** 从旧 Hook 的目标方法反推宿主 ClassLoader（热重载后包回调不会重放）。 */
    @Nullable
    private ClassLoader classLoaderFromOldHooks(
            @NonNull List<XposedInterface.HookHandle> oldHandles, HookConfiguration profile) {
        for (XposedInterface.HookHandle handle : oldHandles) {
            try {
                java.lang.reflect.Executable target = handle.getExecutable();
                Class<?> declaring = target == null ? null : target.getDeclaringClass();
                ClassLoader loader = profile.bindingClassLoader(declaring);
                if (loader != null) {
                    return loader;
                }
            } catch (Throwable t) {
                diagnosticInfo("hot reload class loader inspection failed error="
                        + t.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** 停止线程、解绑 Hook、清理引用。 */
    private void release() {
        for (XposedInterface.HookHandle h : hooks) {
            try {
                h.unhook();
            } catch (Throwable ignored) {
                // 尽力解绑，不中断后续清理。
            }
        }
        hooks.clear();
        Scheduler.MainThread.clear();
        // 等待线程真正结束再返回 true，否则旧代次线程会继续存活（需求 §5.1）。
        Scheduler.resetAndAwait(2000L);
        HookBridge.get().reset();
        TranslationCache.reset();
        RerenderRegistry.clear();
        StructurableTextFactory.reset();
        InlineIconSpan.resetAll();
        ModuleResources.reset();
        installed = false;
        bootstrapInstalled = false;
        hookConfiguration = null;
        HookConfiguration.activate(null);
    }

    private void reloadConfig() {
        try {
            final SharedPreferences prefs = getRemotePreferences(Config.GROUP);
            Config.Snapshot snapshot = Config.read(prefs);
            HookBridge.get().updateConfig(snapshot);
        } catch (Throwable t) {
            HookBridge.get().updateConfig(Config.invalidSnapshot());
        }
    }
}
