package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 配置启用时安装富 Embed 与原生帖子预览的翻译逻辑。
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
        List<XposedInterface.HookHandle> handles = new ArrayList<>(3);
        installRichEmbedBindHook(module, cl, config, handles);
        installPostPreviewBindHook(module, cl, config, handles);
        installPostPreviewRenderHook(module, cl, config, handles);
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
                                    HookConfiguration.member("embedHolderViewField", "itemView"))));
                    try {
                        if (!HookBridge.get().isRerendering()) {
                            try {
                                richEmbedPre(chain, bind, spec);
                            } catch (Throwable ignored) {
                                // 预处理失败不影响宿主绑定。
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
        int accessoryArgument = spec.optInt("accessoryArgument", 1);
        Object accessory = accessoryArgument < 0
                || accessoryArgument >= chain.getArgs().size()
                ? null : chain.getArg(accessoryArgument);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory,
                HookConfiguration.member("embedMessageIdGetter", "getMessageId"));
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object embed = Reflect.call(Reflect.findMethod(
                accessory.getClass(), HookConfiguration.member("embedGetter", "getEmbed"), 0), accessory);
        if (embed == null) {
            return;
        }
        View view = asView(Reflect.getField(chain.getThisObject(),
                HookConfiguration.member("embedHolderViewField", "itemView")));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        // 图标主段优先取描述（正文主体），缺描述时回落标题、再回落首个字段。
        String primary = registerEmbedText(view, messageId, embed,
                HookConfiguration.member("embedDescriptionGetter", "getDescription"),
                holder, bind, args);
        if (primary == null) {
            primary = registerEmbedText(view, messageId, embed,
                    HookConfiguration.member("embedTitleGetter", "getTitle"),
                    holder, bind, args);
        }
        if (primary == null) {
            primary = registerEmbedFields(view, messageId, embed, holder, bind, args);
        } else {
            registerEmbedFields(view, messageId, embed, holder, bind, args);
        }
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
        Object text = Reflect.call(Reflect.findMethod(owner.getClass(), getter, 0), owner);
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
     * @return 首个成功登记的字段状态键（用作图标主段回落）
     */
    @Nullable
    private static String registerEmbedFields(@Nullable View view, String messageId,
                                              Object embed, Object holder, Method bind,
                                              Object[] args) {
        Object fields = Reflect.call(Reflect.findMethod(
                embed.getClass(), HookConfiguration.member("embedFieldsGetter", "getFields"), 0), embed);
        if (!(fields instanceof List)) {
            return null;
        }
        String first = null;
        int count = 0;
        for (Object field : (List<?>) fields) {
            if (field == null || ++count > MAX_FIELDS) {
                break;
            }
            String nameKey = registerEmbedText(view, messageId, field,
                    HookConfiguration.member("embedFieldNameGetter", "getName"),
                    holder, bind, args);
            if (first == null) {
                first = nameKey;
            }
            String valueKey = registerEmbedText(view, messageId, field,
                    HookConfiguration.member("embedFieldValueGetter", "getValue"),
                    holder, bind, args);
            if (first == null) {
                first = valueKey;
            }
        }
        return first;
    }

    // ---------------------------------------------------------- 原生帖子预览（X 等）

    private static void installPostPreviewBindHook(XposedModule module, ClassLoader cl,
                                                   HookConfiguration config,
                                                   List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("embed.postPreviewBind")) {
            return;
        }
        Method bind = config.findMethod("embed.postPreviewBind", cl);
        JSONObject spec = config.hook("embed.postPreviewBind");
        if (bind == null || spec == null) {
            return;
        }
        out.add(module.hook(bind)
                .setId("dat.postPreviewBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 同富 Embed：宿主在调用链内传递，供 footer 上的行内图标使用。
                    InlineIconInjector.enterHost(
                            asView(Reflect.getField(chain.getThisObject(),
                                    HookConfiguration.member("postPreviewHolderViewField", "itemView"))));
                    try {
                        if (!HookBridge.get().isRerendering()) {
                            try {
                                postPreviewPre(chain, bind, spec);
                            } catch (Throwable ignored) {
                                // 预处理失败不影响宿主绑定。
                            }
                        }
                        return chain.proceed();
                    } finally {
                        InlineIconInjector.exitHost();
                    }
                }));
    }

    /**
     * 帖子预览绑定前置。标题是普通 String，不走正文文本转换，因此：
     * <ol>
     *   <li>用 {@link StructurableTextFactory#primitive} 把标题包成
     *       PrimitiveStructurableText，才能进入现有翻译管线；</li>
     *   <li>译文替换由 {@code setPostPreviewEmbed} 的参数替换 Hook 完成
     *       （不走身份映射）；</li>
     *   <li>图标挂在 footer（卡片内支持 StructurableText 转换的字段）上：
     *       仅图标身份映射，footer 文本本身不被替换。</li>
     * </ol>
     */
    private static void postPreviewPre(XposedInterface.Chain chain, Method bind,
                                       JSONObject spec) {
        int modelArgument = spec.optInt("modelArgument", 0);
        Object model = modelArgument < 0 || modelArgument >= chain.getArgs().size()
                ? null : chain.getArg(modelArgument);
        if (model == null) {
            return;
        }
        String messageId = Reflect.callString(model,
                HookConfiguration.member("postPreviewMessageIdGetter", "getMessageId"));
        String title = Reflect.callString(model,
                HookConfiguration.member("postPreviewTitleGetter", "getTitle"));
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId) || title == null) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey(messageId, title);
        Object wrapped = StructurableTextFactory.primitive(title);
        if (key == null || wrapped == null) {
            return; // 空标题，或目标版本缺少 PrimitiveStructurableText 构造能力
        }
        View view = asView(Reflect.getField(chain.getThisObject(),
                HookConfiguration.member("postPreviewHolderViewField", "itemView")));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        if (view != null) {
            HookBridge.get().bindView(view, key);
        }
        RerenderRegistry.register(key, view, holder, bind, args);
        // 正文语义上等价于一条纯文本消息：走同一条翻译触发路径。
        HookBridge.get().onMessageBound(view, key, wrapped);
        Object footer = Reflect.call(Reflect.findMethod(model.getClass(),
                HookConfiguration.member("postPreviewFooterGetter", "getFooter"), 0), model);
        HookBridge.get().rememberIconOnlyContent(key, footer);
    }

    private static void installPostPreviewRenderHook(XposedModule module, ClassLoader cl,
                                                  HookConfiguration config,
                                                  List<XposedInterface.HookHandle> out) {
        if (!config.isEnabled("embed.postPreviewRender")) {
            return;
        }
        Method set = config.findMethod("embed.postPreviewRender", cl);
        JSONObject spec = config.hook("embed.postPreviewRender");
        if (set == null || spec == null) {
            return;
        }
        out.add(module.hook(set)
                .setId("dat.postPreviewRender")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object[] renderArgs = chain.getArgs().toArray();
                    try {
                        substitutePostPreviewTitle(renderArgs, spec);
                    } catch (Throwable ignored) {
                        // 替换失败保留宿主原标题。
                    }
                    return chain.proceed(renderArgs);
                }));
    }

    /**
     * 参数替换：{@code setPostPreviewEmbed(messageId, title, …)} 的
     * 第 0 参是消息 ID、第 1 参是标题（均已在 smali 中核对）。
     * 标题译文就绪时替换之；未就绪时保持原文（译文完成后经重渲染再次进入这里）。
     */
    private static void substitutePostPreviewTitle(Object[] renderArgs, JSONObject spec) {
        int messageIdIndex = spec.optInt("messageIdArgument", 0);
        int titleIndex = spec.optInt("titleArgument", 1);
        if (messageIdIndex < 0 || titleIndex < 0
                || messageIdIndex >= renderArgs.length || titleIndex >= renderArgs.length
                || !(renderArgs[messageIdIndex] instanceof String)) {
            return;
        }
        Object titleArg = renderArgs[titleIndex];
        if (!(titleArg instanceof String)) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey(
                (String) renderArgs[messageIdIndex], (String) titleArg);
        if (key == null) {
            return;
        }
        StateStore.Entry e = StateStore.get().peek(key);
        if (e != null && e.status == StateStore.Status.TRANSLATED
                && !e.showingOriginal && e.translatedText != null) {
            renderArgs[titleIndex] = e.translatedText;
        }
    }

    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
