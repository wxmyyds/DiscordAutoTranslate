package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Embed 翻译 Hook（富 Embed 与原生帖子预览）。
 *
 * <p>已核对 smali（Discord 340.13）的渲染链：
 * <pre>
 * 富 Embed   MessageAccessoriesAdapter → EmbedViewHolder.bind(ChatEventHandler, EmbedMessageAccessory, …9 参)
 *              → EmbedView.setEmbed-fDtLluY(…, Embed, …)
 *              → embed.getTitle()/getDescription()（同一实例）→ TextUtilsKt.toSpannable
 * 字段        EmbedView.setFields-7YO0LfA(List&lt;EmbedField&gt;, …)
 *              → field.getName()/getValue()（StructurableText）→ toSpannable
 * 帖子预览   MessageAccessoriesAdapter → PostPreviewEmbedViewHolder.bind(PostPreviewEmbed, …3 参)
 *              → PostPreviewEmbedView.setPostPreviewEmbed-4itWklQ(messageId, title, subtitle, …16 参)
 *              （title/subtitle 是普通 String，不走 toSpannable；footer 走但属品牌信息）
 * </pre>
 *
 * <p>消息 ID 来源与正文一致：{@code MessageAccessory.getMessageId-3Eiw7ao()}
 * （声明在基类上，前缀匹配可取）。每段 Embed 文本用
 * {@link HookBridge#embedStateKey} 生成独立状态键，与正文共用整套翻译管线
 * （去重/缓存/调度/替换），但互不覆盖。</p>
 *
 * <p>翻译范围：富 Embed 的标题/描述/字段（用户内容）；
 * 帖子预览的标题。作者名、页脚、提供者（站点/品牌名）、URL、图标等
 * 在翻译范围之外，不登记；语言检测器（{@code EnglishDetector}）
 * 也会拦下 URL、过短与非拉丁内容。</p>
 *
 * <p>行内图标：富 Embed 每张卡片只挂一个图标，主段优先取描述
 * （正文主体），回落标题、再回落首个字段；帖子预览挂在 footer 上
 * （标题是 String 不经 toSpannable，footer 经 {@code rememberIconOnlyContent}
 * 的「仅图标」身份解析，文本本身不被替换）。图标点击走
 * {@code onIconClicked(状态键)}：成功切换原文/译文，失败重试，与正文一致。</p>
 */
public final class EmbedHooks {

    private static final String CLS_EMBED_VIEW_HOLDER =
            "com.discord.chat.presentation.message.viewholder.EmbedViewHolder";
    private static final String CLS_EMBED_ACCESSORY =
            "com.discord.chat.presentation.message.messagepart.EmbedMessageAccessory";
    private static final String CLS_POST_PREVIEW_HOLDER =
            "com.discord.chat.presentation.message.viewholder.PostPreviewEmbedViewHolder";
    private static final String CLS_POST_PREVIEW =
            "com.discord.chat.bridge.postpreviewembed.PostPreviewEmbed";
    private static final String CLS_POST_PREVIEW_VIEW =
            "com.discord.chat.presentation.message.view.PostPreviewEmbedView";

    /** EmbedViewHolder.bind(ChatEventHandler, EmbedMessageAccessory, I, I, Function0, SpoilerConfig, Function2, OnLongClickListener, D)。 */
    private static final int EMBED_BIND_PARAM_COUNT = 9;
    /** PostPreviewEmbedViewHolder.bind(PostPreviewEmbed, Function4, SpoilerConfig)。 */
    private static final int POST_PREVIEW_BIND_PARAM_COUNT = 3;
    /** setPostPreviewEmbed-4itWklQ(messageId, title, subtitle, color, footer, …5 String, OnClickListener, ZZZZ, SpoilerConfig)。 */
    private static final int POST_PREVIEW_SET_PARAM_COUNT = 16;

    /** 字段数量上限，防御异常数据导致的反射放大。 */
    private static final int MAX_FIELDS = 25;

    private EmbedHooks() {
    }

    /**
     * 安装 Embed 翻译 Hook。全部为增强能力：任一类或方法在目标版本上不存在时
     * 仅放弃对应部分，不影响既有消息翻译（安装失败不抛出）。
     */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(3);
        installRichEmbedBindHook(module, cl, handles);
        installPostPreviewBindHook(module, cl, handles);
        installPostPreviewSetHook(module, cl, handles);
        return handles;
    }

    // ---------------------------------------------------------- 富 Embed（链接卡片等）

    private static void installRichEmbedBindHook(XposedModule module, ClassLoader cl,
                                                 List<XposedInterface.HookHandle> out) {
        Class<?> holder = Reflect.loadClass(cl, CLS_EMBED_VIEW_HOLDER);
        Class<?> accessory = Reflect.loadClass(cl, CLS_EMBED_ACCESSORY);
        if (holder == null || accessory == null) {
            return;
        }
        Method bind = Reflect.findMethodAt(holder, "bind",
                EMBED_BIND_PARAM_COUNT, 1, accessory);
        if (bind == null) {
            return;
        }
        out.add(module.hook(bind)
                .setId("dat.embedBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 宿主必须在调用链内传递给 toSpannable（同一线程同步）：
                    // 行内图标靠它驱动旋转重绘；重渲染时同样需要，故无条件设置。
                    InlineIconInjector.enterHost(
                            asView(Reflect.getField(chain.getThisObject(), "itemView")));
                    try {
                        if (!HookBridge.get().isRerendering()) {
                            try {
                                richEmbedPre(chain, bind);
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
     * 注册重渲染目标，并触发翻译。替换由 toSpannable 漏斗按各段自己的键完成。
     */
    private static void richEmbedPre(XposedInterface.Chain chain, Method bind) {
        Object accessory = chain.getArg(1);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        Object embed = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getEmbed", 0), accessory);
        if (embed == null) {
            return;
        }
        View view = asView(Reflect.getField(chain.getThisObject(), "itemView"));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        // 图标主段优先取描述（正文主体），缺描述时回落标题、再回落首个字段。
        String primary = registerEmbedText(view, messageId, embed,
                "getDescription", holder, bind, args);
        if (primary == null) {
            primary = registerEmbedText(view, messageId, embed,
                    "getTitle", holder, bind, args);
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
                embed.getClass(), "getFields", 0), embed);
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
                    "getName", holder, bind, args);
            if (first == null) {
                first = nameKey;
            }
            String valueKey = registerEmbedText(view, messageId, field,
                    "getValue", holder, bind, args);
            if (first == null) {
                first = valueKey;
            }
        }
        return first;
    }

    // ---------------------------------------------------------- 原生帖子预览（X 等）

    private static void installPostPreviewBindHook(XposedModule module, ClassLoader cl,
                                                   List<XposedInterface.HookHandle> out) {
        Class<?> holder = Reflect.loadClass(cl, CLS_POST_PREVIEW_HOLDER);
        Class<?> model = Reflect.loadClass(cl, CLS_POST_PREVIEW);
        if (holder == null || model == null) {
            return;
        }
        Method bind = Reflect.findMethodAt(holder, "bind",
                POST_PREVIEW_BIND_PARAM_COUNT, 0, model);
        if (bind == null) {
            return;
        }
        out.add(module.hook(bind)
                .setId("dat.postPreviewBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // 同富 Embed：宿主在调用链内传递，供 footer 上的行内图标使用。
                    InlineIconInjector.enterHost(
                            asView(Reflect.getField(chain.getThisObject(), "itemView")));
                    try {
                        if (!HookBridge.get().isRerendering()) {
                            try {
                                postPreviewPre(chain, bind);
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
     * 帖子预览绑定前置。标题是普通 String、不经 toSpannable，因此：
     * <ol>
     *   <li>用 {@link StructurableTextFactory#primitive} 把标题包成
     *       PrimitiveStructurableText，才能进入现有翻译管线；</li>
     *   <li>译文替换由 {@code setPostPreviewEmbed} 的参数替换 Hook 完成
     *       （不走身份映射）；</li>
     *   <li>图标挂在 footer（卡片内唯一走 toSpannable 的字段）上：
     *       仅图标身份映射，footer 文本本身不被替换。</li>
     * </ol>
     */
    private static void postPreviewPre(XposedInterface.Chain chain, Method bind) {
        Object model = chain.getArg(0);
        if (model == null) {
            return;
        }
        String messageId = Reflect.callString(model, "getMessageId");
        String title = Reflect.callString(model, "getTitle");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId) || title == null) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey(messageId, title);
        Object wrapped = StructurableTextFactory.primitive(title);
        if (key == null || wrapped == null) {
            return; // 空标题，或目标版本缺少 PrimitiveStructurableText 构造能力
        }
        View view = asView(Reflect.getField(chain.getThisObject(), "itemView"));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        if (view != null) {
            HookBridge.get().bindView(view, key);
        }
        RerenderRegistry.register(key, view, holder, bind, args);
        // 正文语义上等价于一条纯文本消息：走同一条翻译触发路径。
        HookBridge.get().onMessageBound(view, key, wrapped);
        Object footer = Reflect.call(Reflect.findMethod(
                model.getClass(), "getFooter", 0), model);
        HookBridge.get().rememberIconOnlyContent(key, footer);
    }

    private static void installPostPreviewSetHook(XposedModule module, ClassLoader cl,
                                                  List<XposedInterface.HookHandle> out) {
        Class<?> viewCls = Reflect.loadClass(cl, CLS_POST_PREVIEW_VIEW);
        if (viewCls == null) {
            return;
        }
        Method set = null;
        for (Method m : viewCls.getDeclaredMethods()) {
            if (!m.getName().startsWith("setPostPreviewEmbed") || m.getName().contains("$")) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != POST_PREVIEW_SET_PARAM_COUNT || ps[0] != String.class) {
                continue;
            }
            m.setAccessible(true);
            set = m;
            break;
        }
        if (set == null) {
            return;
        }
        out.add(module.hook(set)
                .setId("dat.postPreviewSet")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object[] renderArgs = chain.getArgs().toArray();
                    try {
                        substitutePostPreviewTitle(renderArgs);
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
    private static void substitutePostPreviewTitle(Object[] renderArgs) {
        if (renderArgs.length < 2 || !(renderArgs[0] instanceof String)) {
            return;
        }
        Object titleArg = renderArgs[1];
        if (!(titleArg instanceof String)) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey((String) renderArgs[0], (String) titleArg);
        if (key == null) {
            return;
        }
        StateStore.Entry e = StateStore.get().peek(key);
        if (e != null && e.status == StateStore.Status.TRANSLATED
                && !e.showingOriginal && e.translatedText != null) {
            renderArgs[1] = e.translatedText;
        }
    }

    // ------------------------------------------------------------------ 工具

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
