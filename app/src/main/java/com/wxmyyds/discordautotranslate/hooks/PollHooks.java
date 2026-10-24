package com.wxmyyds.discordautotranslate.hooks;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

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
 * 与正文/Embed 共用整套翻译管线且互不覆盖。</p>
 *
 * <p><b>行内图标</b>：挂在<b>问题</b>文本末尾（{@code pollView.binding.questionText}）。
 * 该视图不在宿主的 NestedClickableSpan 分发链上，命中判定由自建触摸监听
 * （触点 → 字符位置 → span 区间）完成；点击行为与正文一致（成功切换原文/译文、
 * 失败重试），旋转动画复用 {@link InlineIconSpan}。选项文本不挂图标。</p>
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
        Method bind = Reflect.findMethodAt(holder, "bind",
                POLL_BIND_PARAM_COUNT, 0, accessory);
        if (bind == null) {
            XLog.w("PollViewHolder.bind not found (paramCount=" + POLL_BIND_PARAM_COUNT + ")");
            return handles;
        }
        handles.add(module.hook(bind)
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
                    Object result = chain.proceed(renderArgs);
                    // 图标同样无状态追加：bind 后问题文本已定型，随当前状态着色。
                    try {
                        attachQuestionIcon(chain.getArg(0),
                                asView(Reflect.getField(chain.getThisObject(), "itemView")));
                    } catch (Throwable t) {
                        XLog.w("poll question icon failed", t);
                    }
                    return result;
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
            if (item == null) {
                continue;
            }
            if (++count > MAX_ANSWERS) {
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

    // ------------------------------------------------------------------ 问题图标

    /**
     * 在投票问题文本末尾追加行内图标。
     *
     * <p>路径（smali 已核对）：{@code pollView.binding.questionText} 是普通
     * {@code TextView.setText(String)} 直排，不在宿主的 NestedClickableSpan
     * 分发链上，因此命中判定由 {@link PollIconTouch} 自行按触点换算字符位置完成，
     * 点击直接进入 {@code onIconClicked(状态键)}，与正文图标行为一致。</p>
     */
    private static void attachQuestionIcon(@Nullable Object accessory, @Nullable View pollView) {
        if (accessory == null || pollView == null) {
            return;
        }
        String messageId = Reflect.callString(accessory, "getMessageId");
        if (messageId == null) {
            return;
        }
        Object data = Reflect.call(Reflect.findMethod(
                accessory.getClass(), "getData", 0), accessory);
        Object questionMedia = data == null ? null : Reflect.call(
                Reflect.findMethod(data.getClass(), "getQuestion", 0), data);
        String rawText = questionMedia == null
                ? null : Reflect.callString(questionMedia, "getText");
        if (rawText == null) {
            return;
        }
        String key = HookBridge.get().embedTextStateKey(messageId, rawText);
        if (key == null) {
            return;
        }
        StateStore.Entry entry = StateStore.get().peek(key);
        // 未触发翻译（IDLE/无条目）不显示，与正文注入器口径一致。
        if (entry == null || entry.status == StateStore.Status.IDLE) {
            return;
        }
        TextView questionText = questionTextViewOf(pollView);
        if (questionText == null) {
            return;
        }
        CharSequence current = questionText.getText();
        if (current instanceof Spanned && ((Spanned) current).getSpans(
                0, current.length(), InlineIconSpan.class).length > 0) {
            return; // 本帧已带图标
        }
        Drawable source = InlineIconInjector.iconFor(questionText.getContext(), entry.status);
        Drawable tinted = InlineIconSpan.tintForState(
                source, entry.status, entry.showingOriginal);
        InlineIconSpan icon = InlineIconSpan.create(tinted,
                questionText.getContext().getResources().getDisplayMetrics().density,
                entry.status, key);
        if (icon == null) {
            return;
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder(
                current == null ? "" : current);
        // 与正文注入相同的两格结构：零宽点击前置 + 图标占位；span 覆盖两格，
        // 触点落在任一格都命中。问题视图无其他触摸行为，监听器可安全接管。
        int start = ssb.length();
        ssb.append('\uFEFF').append('\u00A0');
        ssb.setSpan(icon, start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        questionText.setText(ssb);
        icon.bindHost(questionText);
        questionText.setOnTouchListener(new PollIconTouch(key));
        XLog.d("poll question icon attached status=" + entry.status);
    }

    /** 取投票问题文本视图：{@code pollView.binding.questionText}。 */
    @Nullable
    private static TextView questionTextViewOf(View pollView) {
        Object binding = Reflect.getField(pollView, "binding");
        Object questionText = Reflect.getField(binding, "questionText");
        return questionText instanceof TextView ? (TextView) questionText : null;
    }

    /**
     * 图标触摸命中：把触点换算为字符位置，落在图标 span 区间则触发切换/重试。
     *
     * <p><b>必须在 ACTION_DOWN 命中时就认领手势</b>（返回 true）：问题视图自身
     * 不可点击，若 DOWN 放行，手势会被上层可点击的行/卡片认领，UP 不会再送到
     * 本视图。命中 DOWN 后整条手势归本视图，UP 到达即触发；其余情况一律放行，
     * 不影响投票与消息本身的交互。</p>
     */
    private static final class PollIconTouch implements View.OnTouchListener {
        private final String key;

        PollIconTouch(String key) {
            this.key = key;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (!(v instanceof TextView)) {
                return false;
            }
            TextView tv = (TextView) v;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return hitIcon(tv, event);
                case MotionEvent.ACTION_UP:
                    if (hitIcon(tv, event)) {
                        XLog.d("poll icon clicked key=" + key);
                        HookBridge.get().onIconClicked(key);
                        tv.performClick();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private static boolean hitIcon(TextView tv, MotionEvent event) {
            android.text.Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || !(text instanceof Spanned)) {
                return false;
            }
            int line = layout.getLineForVertical(
                    (int) event.getY() - tv.getTotalPaddingTop() + tv.getScrollY());
            float x = event.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
            int offset = layout.getOffsetForHorizontal(line, x);
            return ((Spanned) text).getSpans(offset, offset, InlineIconSpan.class).length > 0;
        }
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

    @Nullable
    private static View asView(@Nullable Object o) {
        return o instanceof View ? (View) o : null;
    }
}
