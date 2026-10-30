# libxposed 入口类由框架按名称实例化，必须保留类名与公开无参构造。
# 规则来自 libxposed/api README（模块侧 ProGuard 规则），原型阶段不做名称混淆以降低风险。
-dontwarn io.github.libxposed.annotation.**

-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed 框架与服务端接口：运行时由 LSPosed 提供，不参与混淆。
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.annotation.** { *; }
-keep class io.github.libxposed.service.** { *; }

# 设置 Activity 与 libxposed 服务提供者由系统按名称实例化。
-keep public class io.github.wxmyyds.discordautotranslate.ui.SettingsActivity { public <init>(); }
