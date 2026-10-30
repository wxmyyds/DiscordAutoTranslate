package io.github.wxmyyds.discordautotranslate;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ModuleResources;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.hooks.EmbedHooks;
import io.github.wxmyyds.discordautotranslate.hooks.HookBridge;
import io.github.wxmyyds.discordautotranslate.hooks.InlineIconSpan;
import io.github.wxmyyds.discordautotranslate.hooks.PollHooks;
import io.github.wxmyyds.discordautotranslate.hooks.RerenderRegistry;
import io.github.wxmyyds.discordautotranslate.hooks.StructurableTextFactory;
import io.github.wxmyyds.discordautotranslate.hooks.MessageHooks;

import java.util.List;

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
    private volatile ClassLoader targetClassLoader;

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
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
        if (installed) {
            // 防止重复安装（需求 §风险 6：不得重复注册 Hook）。
            return;
        }
        targetClassLoader = param.getClassLoader();

        try {
            install(param.getClassLoader());
            installed = true;
        } catch (Throwable ignored) {
            // Hook 失败不能导致 Discord 崩溃（需求 §5.1）：模块保持不激活，宿主正常渲染。
            installed = false;
        }
    }

    /**
     * 热重载：在旧代码中执行。返回 true 前必须停止本模块拥有的线程、
     * 释放引用，否则旧代次会被新代次长期持有（需求 §5.1、§风险 6）。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        try {
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
            return;
        }
        try {
            install(cl);
            installed = true;
        } catch (Throwable ignored) {
            // 重装失败：模块保持不激活，等待下次 onPackageReady 或重启。
        }
    }

    // ------------------------------------------------------------ 安装与卸载

    private void install(ClassLoader cl) {
        StructurableTextFactory.init(cl);
        Scheduler.MainThread.install();
        reloadConfig();
        // 行内图标所需的 Drawable 由 ModuleResources 按需解析（内部已缓存），
        // 不再需要在安装阶段预加载到某个视图类。

        List<XposedInterface.HookHandle> created = MessageHooks.install(this, cl);
        if (created.size() < MessageHooks.MIN_REQUIRED_HOOKS) {
            // 半安装状态会让模块“看似已启用、实际不工作”；回滚并等待重启。
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
        // Embed 翻译是增强能力：目标版本缺少对应类时静默降级，不影响核心 Hook 数。
        hooks.addAll(EmbedHooks.install(this, cl));
        // 投票卡片翻译同为增强能力，独立降级。
        hooks.addAll(PollHooks.install(this, cl));
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
                    return loader;
                }
            } catch (Throwable ignored) {
                // 反推失败按 null 处理，走下一次 onPackageReady 的正常路径。
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
        ModuleResources.reset();
        installed = false;
    }

    private void reloadConfig() {
        try {
            final SharedPreferences prefs = getRemotePreferences(Config.GROUP);
            Config.Snapshot snapshot = Config.read(prefs);
            HookBridge.get().updateConfig(snapshot);
        } catch (Throwable ignored) {
            // 读取失败沿用上次配置（首次则为默认值）。
        }
    }
}
