package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 配置启用时安装富 Embed 的翻译逻辑。
 * 目标方法、参数位置和成员名来自版本 profile；文本内容复用统一的翻译与转换管线。
 */
public final class EmbedHooks {

    /** 字段数量上限，防御异常数据导致的反射放大。 */
    private static final int MAX_FIELDS = 25;

    private EmbedHooks() {
    }

    /**
     * 安装 Embed 翻译 Hook。全部为增强能力：任一类或方法在目标版本上不存在时
     * 仅放弃对应部分，不影响既有消息翻译（安装失败不抛出）。
     */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl,
                                                            HookConfiguration config) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(1);
        installRichEmbedBindHook(module, cl, config, handles);
        return handles;
    }

    // ---------------------------------------------------------- 富 Embed（链接卡片等）

    private static void installRichEmbedBindHook(XposedModule module, ClassLoader cl,
                                                 HookConfiguration config,
                                                 List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("embed.bind")) {
            return;
        }
        Method bind = config.findMethod("embed.bind", cl);
        JSONObject spec = config.hook("embed.bind");
        if (bind == null || spec == null) {
            return;
        }
        out.add(module.hook(bind)
                .setId("dat.embedBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 宿主必须在同步文本转换调用链内传递给图标注入器；重渲染也需要，故无条件设置。
                    InlineIconInjector.enterHost(
                            asView(Reflect.getField(chain.getThisObject(),
                                    HookConfiguration.member("embedHolderViewField"))));
                    try {
                        if (!HookBridge.get().isRerendering()) {
                            try {
                                richEmbedPre(chain, bind, spec);
                            } catch (Throwable t) {
                                HookDiagnostics.warnOnce("embed.bind.failed", "embed binding failed error="
                                        + t.getClass().getSimpleName());
                            }
                        }
                        return chain.proceed();
                    } finally {
                        InlineIconInjector.exitHost();
                    }
                }));
    }

    /**
     * 富 Embed 绑定前置：登记各文本段（描述/标题/字段）的状态键与身份映射，
     * 注册重渲染目标，并触发翻译。替换由统一文本转换 Hook 按每段状态键完成。
     */
    private static void richEmbedPre(XposedInterface.Chain chain, Method bind,
                                     JSONObject spec) {
        int accessoryArgument = spec.optInt("accessoryArgument", -1);
        Object accessory = accessoryArgument < 0
                || accessoryArgument >= chain.getArgs().size()
                ? null : chain.getArg(accessoryArgument);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory,
                HookConfiguration.member("embedMessageIdGetter"));
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object embed = Reflect.call(Reflect.findMethodExact(
                accessory.getClass(), HookConfiguration.member("embedGetter"), 0), accessory);
        if (embed == null) {
            return;
        }
        View view = asView(Reflect.getField(chain.getThisObject(),
                HookConfiguration.member("embedHolderViewField")));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        // 登记所有实际存在的段，图标只标记首个有文本的段（描述、标题、字段）。
        String description = registerEmbedText(view, messageId, embed,
                HookConfiguration.member("embedDescriptionGetter"), holder, bind, args);
        String title = registerEmbedText(view, messageId, embed,
                HookConfiguration.member("embedTitleGetter"), holder, bind, args);
        String firstField = registerEmbedFields(view, messageId, embed, holder, bind, args);
        String primary = description != null ? description : title != null ? title : firstField;
        if (primary != null) {
            HookBridge.get().markEmbedIconKey(primary);
        }
    }

    /** 登记单段 StructurableText：状态键 + 身份映射 + 重渲染目标 + 触发翻译。
     *
     * @return 该段的状态键；文本为空或类型未知时返回 null
     */
    @Nullable
    private static String registerEmbedText(@Nullable View view, String messageId,
                                            Object owner, String getter,
                                            Object holder, Method bind, Object[] args) {
        Object text = Reflect.call(Reflect.findMethodExact(owner.getClass(), getter, 0), owner);
        if (text == null) {
            return null;
        }
        String key = HookBridge.get().embedStateKey(messageId, text);
        if (key == null) {
            return null; // 空文本或未知 StructurableText 类型
        }
        HookBridge.get().rememberContent(key, text);
        if (view != null) {
            HookBridge.get().bindView(view, key);
        }
        RerenderRegistry.register(key, view, holder, bind, args);
        HookBridge.get().onMessageBound(view, key, text);
        return key;
    }

    /** Embed 字段：name 与 value 均为 StructurableText，同为用户可见内容。
     *
     * @return 首个有正文的字段状态键（每张卡片选择一个图标承载段）
     */
    @Nullable
    private static String registerEmbedFields(@Nullable View view, String messageId,
                                              Object embed, Object holder, Method bind,
                                              Object[] args) {
        Object fields = Reflect.call(Reflect.findMethodExact(
                embed.getClass(), HookConfiguration.member("embedFieldsGetter"), 0), embed);
        if (!(fields instanceof List)) {
            return null;
        }
        String first = null;
        int count = 0;
        for (Object field : (List<?>) fields) {
            if (field == null) {
                continue;
            }
            if (++count > MAX_FIELDS) {
                throw new IllegalArgumentException("Embed field count exceeds host limit");
            }
            String nameKey = registerEmbedText(view, messageId, field,
                    HookConfiguration.member("embedFieldNameGetter"),
                    holder, bind, args);
            if (first == null) {
                first = nameKey;
            }
            String valueKey = registerEmbedText(view, messageId, field,
                    HookConfiguration.member("embedFieldValueGetter"),
                    holder, bind, args);
            if (first == null) {
                first = valueKey;
            }
        }
        return first;
    }

    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
