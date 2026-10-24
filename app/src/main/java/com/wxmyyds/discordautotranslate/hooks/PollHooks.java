package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Reflect;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.XLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 投票卡片翻译 Hook。
 *
 * <p><b>已核对 smali（Discord 340.13）的渲染链</b>：
 * <pre>
 * MessageAccessoriesAdapter → PollViewHolder.bind(PollMessageAccessory, F3, F3, F8)
 *   → PollPresenter.bind → BasePollWithRecyclerView.bind
 *       问题：PollData.getQuestion().getText()（String）→ TextView.setText 直排
 *       选项：PollMessageAccessory.getAnswers()（List&lt;PollAnswerAccessory&gt;）
 *              → PollAnswerAdapter → PollMediaView.setMedia → DraweeSpanStringBuilder
 * </pre>
 * 投票文本<b>全程不经 {@code toSpannable}</b>（无 Markdown 管线），因此采用
 * 「绑定参数深层重建」：bind 时若某段文本的译文已就绪，用 Kotlin data class 的
 * {@code copy} 链把译文换入 accessory（PollMessageAccessory → PollData → PollMedia /
 * PollMessageAccessory → PollAnswerAccessory → PollAnswer → PollMedia）；未就绪的
 * 文本保持原实例。译文完成后的重渲染（重新 bind）使替换生效。</p>
 *
 * <p>状态键复用 {@link HookBridge#embedTextStateKey}（行消息 ID + 内容哈希），
 * 与正文/Embed 共用整套翻译管线且互不覆盖。投票卡片<b>不注入行内图标</b>：
 * 图标注入依赖 toSpannable 漏斗，投票没有可借道的文本渲染点。</p>
 */
public final class PollHooks {

    private static final String CLS_POLL_HOLDER =
            "com.discord.chat.presentation.message.viewholder.PollViewHolder";
    private static final String CLS_POLL_ACCESSORY =
            "com.discord.chat.presentation.message.messagepart.PollMessageAccessory";

    /** PollViewHolder.bind(PollMessageAccessory, Function3, Function3, Function8)。 */
    private static final int POLL_BIND_PARAM_COUNT = 4;

    /** 选项数量上限，防御异常数据导致的反射放大。 */
    private static final int MAX_ANSWERS = 25;

    private PollHooks() {
    }

    /**
     * 安装投票翻译 Hook。增强能力：目标版本缺少对应类时静默降级，
     * 不影响既有功能（安装失败不抛出）。
     */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(1);
        Class<?> holder = Reflect.loadClass(cl, CLS_POLL_HOLDER);
        Class<?> accessory = Reflect.loadClass(cl, CLS_POLL_ACCESSORY);
        if (holder == null || accessory == null) {
            XLog.d("PollViewHolder/PollMessageAccessory not found; poll translation disabled");
            return handles;
        }
        Method bind = findBindMethod(holder, accessory, POLL_BIND_PARAM_COUNT, 0);
        if (bind == null) {
            XLog.w("PollViewHolder.bind not found (paramCount=" + POLL_BIND_PARAM_COUNT + ")");
            return handles;
        }
        out.add(module.hook(bind)
                .setId("dat.pollBind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!HookBridge.get().isRerendering()) {
                        try {
                            registerPollTexts(chain, bind);
                        } catch (Throwable t) {
                            XLog.w("poll bind hook failed", t);
                        }
                    }
                    // 无状态替换：每次 bind（含重渲染与滑回）都按当前译文重建。
                    Object[] renderArgs = chain.getArgs().toArray();
                    try {
                        renderArgs[0] = translatePollAccessory(renderArgs[0]);
                    } catch (Throwable t) {
                        XLog.w("poll accessory rebuild failed", t);
                    }
                    return chain.proceed(renderArgs);
                }));
        XLog.i("hooked PollViewHolder.bind");
        return handles;
    }

    // ------------------------------------------------------------------ 登记

    /** 登记问题与各选项文本：状态键 + 触发翻译 + 重渲染目标（原始实参）。 */
    private static void registerPollTexts(XposedInterface.Chain chain, Method bind) {
        Object accessory = chain.getArg(0);
        if (accessory == null) {
            return;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null || !HookBridge.looksLikeSnowflake(messageId)) {
            return;
        }
        View view = asView(Reflect.getField(chain.getThisObject(), "itemView"));
        Object holder = chain.getThisObject();
        Object[] args = chain.getArgs().toArray();
        Object data = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getData", 0), accessory);
        if (data != null) {
            Object questionMedia = Reflect.call(Reflect.findMethod(
                    data.getClass(), "getQuestion", 0), data);
            registerPollText(view, messageId, mediaText(questionMedia), holder, bind, args);
        }
        Object answers = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getAnswers", 0), accessory);
        if (!(answers instanceof List)) {
            return;
        }
        int count = 0;
        for (Object item : (List<?>) answers) {
            if (item == null || ++count > MAX_ANSWERS) {
                break;
            }
            registerPollText(view, messageId, answerMediaText(item), holder, bind, args);
        }
    }

    /** 取选项条目（PollAnswerAccessory）承载的文本。 */
    @Nullable
    private static String answerMediaText(Object item) {
        Object answer = Reflect.call(Reflect.findMethod(
                item.getClass(), "getAnswer", 0), item);
        return mediaText(answer);
    }

    /** 取 PollMedia 形态对象的文本（PollMedia / PollAnswer 兼容）。 */
    @Nullable
    private static String mediaText(@Nullable Object mediaHolder) {
        if (mediaHolder == null) {
            return null;
        }
        Object media = mediaHolder;
        Object direct = Reflect.call(Reflect.findMethod(
                mediaHolder.getClass(), "getPollMedia", 0), mediaHolder);
        if (direct != null) {
            media = direct;
        }
        return Reflect.callString(media, "getText");
    }

    private static void registerPollText(@Nullable View view, String messageId,
                                         @Nullable String rawText, Object holder,
                                         Method bind, Object[] args) {
        if (rawText == null) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey(messageId, rawText);
        Object wrapped = StructurableTextFactory.primitive(rawText);
        if (key == null || wrapped == null) {
            return;
        }
        if (view != null) {
            HookBridge.get().bindView(view, key);
        }
        RerenderRegistry.register(key, view, holder, bind, args);
        HookBridge.get().onMessageBound(view, key, wrapped);
        XLog.d("poll text registered msg=" + messageId);
    }

    // ------------------------------------------------------------------ 重建

    /**
     * 按已就绪的译文重建投票 accessory；无任何替换时原样返回。
     * 替换链：PollMessageAccessory →（PollData → PollMedia 问题）
     * 与（List&lt;PollAnswerAccessory&gt; → PollAnswer → PollMedia 选项）。
     */
    static Object translatePollAccessory(@Nullable Object accessory) {
        if (accessory == null) {
            return accessory;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null) {
            return accessory;
        }
        Object result = accessory;

        Object data = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getData", 0), accessory);
        if (data != null) {
            Object questionMedia = Reflect.call(Reflect.findMethod(
                    data.getClass(), "getQuestion", 0), data);
            Object translatedMedia = translateMedia(questionMedia, messageId);
            if (translatedMedia != questionMedia) {
                Object newData = copyWithReplaced(data, questionMedia, translatedMedia);
                Object newAccessory = newData == null
                        ? null : copyWithReplaced(result, data, newData);
                if (newAccessory != null) {
                    result = newAccessory;
                }
            }
        }

        Object answers = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getAnswers", 0), accessory);
        if (answers instanceof List) {
            List<?> list = (List<?>) answers;
            List<Object> newList = null;
            int count = 0;
            for (int i = 0; i < list.size() && count < MAX_ANSWERS; i++) {
                Object item = list.get(i);
                if (item == null) {
                    continue;
                }
                count++;
                Object replaced = translateAnswerAccessory(item, messageId);
                if (replaced == item) {
                    continue;
                }
                if (newList == null) {
                    newList = new ArrayList<>(list);
                }
                newList.set(i, replaced);
            }
            if (newList != null) {
                Object newAccessory = copyWithReplaced(result, answers, newList);
                if (newAccessory != null) {
                    result = newAccessory;
                }
            }
        }
        return result;
    }

    /** 重建单个选项条目：PollAnswerAccessory → PollAnswer → PollMedia 文本。 */
    private static Object translateAnswerAccessory(Object item, String messageId) {
        Object answer = Reflect.call(Reflect.findMethod(
                item.getClass(), "getAnswer", 0), item);
        if (answer == null) {
            // smali 中列表元素均为 PollAnswerAccessory；其他形态不做猜测性重建。
            return item;
        }
        Object media = Reflect.call(Reflect.findMethod(
                answer.getClass(), "getPollMedia", 0), answer);
        Object translatedMedia = translateMedia(media, messageId);
        if (translatedMedia == media) {
            return item;
        }
        Object newAnswer = copyWithReplaced(answer, media, translatedMedia);
        if (newAnswer == null) {
            return item;
        }
        Object newItem = copyWithReplaced(item, answer, newAnswer);
        return newItem != null ? newItem : item;
    }

    /** 译文就绪则重建 PollMedia（换入译文本本），否则原样返回。 */
    private static Object translateMedia(@Nullable Object media, String messageId) {
        if (media == null) {
            return media;
        }
        String text = Reflect.callString(media, "getText");
        if (text == null) {
            return media;
        }
        String key = HookBridge.get().embedTextStateKey(messageId, text);
        if (key == null) {
            return media;
        }
        StateStore.Entry e = StateStore.get().peek(key);
        if (e == null || e.status != StateStore.Status.TRANSLATED
                || e.showingOriginal || e.translatedText == null) {
            return media;
        }
        Object rebuilt = copyWithReplaced(media, text, e.translatedText);
        return rebuilt != null ? rebuilt : media;
    }

    // ------------------------------------------------------------------ 通用 copy

    /**
     * 用 Kotlin data class 的 {@code copy} 重建对象，把值等于 {@code expectedOld}
     * （按实例身份比较）的组件替换为 {@code replacement}，其余组件取 {@code componentN} 原值。
     *
     * <p>方法名兼容 Kotlin 名字修饰（{@code copy-j8a4Y88} / {@code component1-3Eiw7ao}）：
     * 前缀匹配并排除 {@code $default} 合成方法。</p>
     *
     * @return 重建后的对象；找不到 copy、未命中目标组件或调用失败时返回 null
     */
    @Nullable
    static Object copyWithReplaced(Object node, Object expectedOld, Object replacement) {
        if (node == null) {
            return null;
        }
        Method copy = findPrefixMethod(node.getClass(), "copy", 1);
        if (copy == null) {
            return null;
        }
        Class<?>[] types = copy.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean replaced = false;
        for (int i = 0; i < types.length; i++) {
            Object component = callComponent(node, i + 1);
            if (!replaced && component == expectedOld) {
                args[i] = replacement;
                replaced = true;
            } else {
                args[i] = component;
            }
        }
        if (!replaced) {
            return null;
        }
        return Reflect.call(copy, node, args);
    }

    /** 按名字前缀查找方法（参数个数下限、排除 {@code $} 合成方法）。 */
    @Nullable
    private static Method findPrefixMethod(Class<?> cls, String prefix, int minParams) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().startsWith(prefix) || m.getName().contains("$")) {
                continue;
            }
            if (m.getParameterCount() < minParams) {
                continue;
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** 取第 n 个组件（componentN，兼容名字修饰后缀；避免 component1 前缀误配 component11）。 */
    @Nullable
    private static Object callComponent(Object node, int n) {
        Method fallback = null;
        for (Method m : node.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            String name = m.getName();
            if (name.equals("component" + n)) {
                m.setAccessible(true);
                return Reflect.call(m, node);
            }
            if (name.startsWith("component" + n + "-")) {
                fallback = m;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
            return Reflect.call(fallback, node);
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    /** 按名字与参数个数定位 {@code bind}，并要求指定下标的参数与模型类型可赋值匹配。 */
    @Nullable
    private static Method findBindMethod(Class<?> holder, Class<?> modelType,
                                         int paramCount, int modelIndex) {
        for (Method m : holder.getDeclaredMethods()) {
            if (!"bind".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != paramCount || modelIndex >= ps.length) {
                continue;
            }
            if (!ps[modelIndex].isAssignableFrom(modelType)
                    && !modelType.isAssignableFrom(ps[modelIndex])) {
                continue;
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
