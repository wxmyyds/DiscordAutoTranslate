package com.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import com.wxmyyds.discordautotranslate.core.Config;
import com.wxmyyds.discordautotranslate.core.EnglishDetector;
import com.wxmyyds.discordautotranslate.core.Scheduler;
import com.wxmyyds.discordautotranslate.core.StateStore;
import com.wxmyyds.discordautotranslate.core.TextExtractor;
import com.wxmyyds.discordautotranslate.core.TranslationCache;
import com.wxmyyds.discordautotranslate.core.TranslationException;
import com.wxmyyds.discordautotranslate.core.XLog;
import com.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染层与翻译状态之间的桥。
 *
 * <p>职责：
 * <ol>
 *   <li>维护 <b>正文视图 → 消息 ID</b> 映射。必须如此：RecyclerView 会复用
 *       {@code MessageContentView} 实例，译文状态绝不能存在 View 上（需求 §风险 2）。</li>
 *   <li>保存每条消息最近一次的原始渲染参数，用于翻译完成后重新渲染。</li>
 *   <li>提供“原文/译文”切换与失败重试入口，供图标点击调用。</li>
 * </ol>
 */
public final class HookBridge {

    /** Discord 消息 ID 是雪花 ID：纯数字，长度约 17–20。 */
    private static final int SNOWFLAKE_MIN = 15;
    private static final int SNOWFLAKE_MAX = 21;

    /** 重渲染期间的标记：避免重渲染调用再次被 Hook 拦截并覆盖已保存的“原文”。 */
    private static final ThreadLocal<Boolean> RERENDERING = new ThreadLocal<>();

    private static final HookBridge INSTANCE = new HookBridge();

    public static HookBridge get() {
        return INSTANCE;
    }

    /** 正文视图 → 消息 ID */
    private final Map<View, String> viewToMessageId = new ConcurrentHashMap<>();

    /** 正文视图 → 最近一次“原始”渲染实参（用于重新渲染） */
    private final Map<View, Object[]> originalRenderArgs = new ConcurrentHashMap<>();

    /** 消息 ID → 原始 StructurableText（即使视图已回收也能重试） */
    private final Map<String, Object> rawContent = new ConcurrentHashMap<>();

    /** 消息 ID → 最近承载它的视图 */
    private final Map<String, View> lastView = new ConcurrentHashMap<>();

    private volatile Config.Snapshot config = Config.read(null);
    private volatile Scheduler.Engine engine = new JsonApiEngine(config);

    private HookBridge() {
    }

    // ---------------------------------------------------------------- 配置与引擎

    public Config.Snapshot config() {
        return config;
    }

    /**
     * 更新配置。会影响翻译结果的配置变化（URL/模型/目标语言/模板/提取路径）发生后，
     * 递增状态代次使旧译文与在途任务失效（需求 §5.6、§5.7）。
     */
    public void updateConfig(Config.Snapshot c) {
        if (c == null) {
            return;
        }
        Config.Snapshot old = this.config;
        this.config = c;
        XLog.setVerbose(c.verboseLog);

        // 并发上限即时生效（需求 §5.7 普通配置变更尽量即时生效）。
        Scheduler.get().setMaxConcurrency(c.maxConcurrency);

        if (old == null || !old.version().equals(c.version())) {
            XLog.i("translation config changed, invalidating cached state");
            StateStore.get().onNewGeneration();
            TranslationCache.get().clear();
        }
        // 引擎随配置重建，保证下一次请求使用最新配置。
        this.engine = new JsonApiEngine(c);
    }

    public Scheduler.Engine engine() {
        return engine;
    }

    // ---------------------------------------------------------------- 视图映射

    /** 记录正文视图当前绑定的消息 ID。列表复用时会被覆盖，始终反映最新绑定。 */
    public void bindView(View contentView, String messageId) {
        if (contentView == null || messageId == null) {
            return;
        }
        String prev = viewToMessageId.put(contentView, messageId);
        if (prev != null && !prev.equals(messageId)) {
            // 视图被复用到另一条消息：丢弃该视图的旧渲染参数，防止译文串位（需求 §风险 2）。
            originalRenderArgs.remove(contentView);
            lastView.remove(prev);
        }
        lastView.put(messageId, contentView);
    }

    @Nullable
    public String messageIdOf(@Nullable View contentView) {
        return contentView == null ? null : viewToMessageId.get(contentView);
    }

    /** 取视图最近一次渲染所用的 StructurableText（即原文结构）。 */
    @Nullable
    public Object lastStructurable(@Nullable View contentView) {
        if (contentView == null) {
            return null;
        }
        Object[] args = originalRenderArgs.get(contentView);
        return (args != null && args.length > 1) ? args[1] : null;
    }

    public void forgetView(View contentView) {
        String id = viewToMessageId.remove(contentView);
        originalRenderArgs.remove(contentView);
        if (id != null) {
            lastView.remove(id);
        }
    }

    /** 清空全部映射。热重载与配置重置时调用。 */
    public void reset() {
        viewToMessageId.clear();
        originalRenderArgs.clear();
        rawContent.clear();
        lastView.clear();
    }

    // ---------------------------------------------------------------- 渲染拦截

    /** 当前是否处于“重渲染”调用中。 */
    public boolean isRerendering() {
        return Boolean.TRUE.equals(RERENDERING.get());
    }

    /**
     * 记录“原始”渲染参数，并在有译文时把 {@code messageContent}（实参下标 1）替换为译文。
     *
     * @return 实际应传给原方法的实参；无替换时返回传入的同一数组
     */
    public Object[] interceptRender(View contentView, Object[] args,
                                    @Nullable Object structurableText,
                                    @Nullable String translatedText) {
        if (contentView != null && args != null) {
            // 只记录“原文”参数；重渲染不应覆盖它，否则译文会取代原文被保存下来。
            originalRenderArgs.put(contentView, args.clone());
            if (structurableText != null) {
                String id = viewToMessageId.get(contentView);
                if (id != null) {
                    rawContent.put(id, structurableText);
                }
            }
        }
        if (translatedText == null || args == null || args.length < 2) {
            return args;
        }

        if (!StructurableTextFactory.isRenderableType(structurableText)) {
            // 渲染器只接受 Primitive/Annotated 两种类型，其他类型会被抛异常。
            XLog.d("skip substitute: unsupported content type "
                    + (structurableText == null ? "null" : structurableText.getClass().getName()));
            return args;
        }

        // 保留 Markdown：对 Annotated 结构只替换自然语言文本节点，
        // 结构化节点（提及/表情/链接/代码块）原样保留。
        Object replacement = StructurableTextFactory.forTranslation(structurableText, translatedText);
        if (replacement == null) {
            if (!StructurableTextFactory.isInitTried()) {
                XLog.w("StructurableTextFactory not initialized; translation display disabled");
            }
            return args;
        }

        Object[] copy = args.clone();
        copy[1] = replacement;
        return copy;
    }

    /**
     * 翻译完成后触发重渲染：用已保存的原文参数重新调用 {@code setMessageContent}。
     * 不发起新的翻译请求（需求 §2.4）。
     */
    public void requestRerender(@Nullable View contentView, @Nullable Method setMessageContent) {
        if (contentView == null || setMessageContent == null) {
            return;
        }
        Object[] original = originalRenderArgs.get(contentView);
        if (original == null) {
            return;
        }
        String messageId = messageIdOf(contentView);
        StateStore.Entry e = messageId == null ? null : StateStore.get().peek(messageId);
        String translated = (e != null && e.status == StateStore.Status.TRANSLATED
                && !e.showingOriginal) ? e.translatedText : null;

        Object[] effective = original;
        if (translated != null && original.length > 1) {
            Object replacement = StructurableTextFactory.forTranslation(original[1], translated);
            if (replacement != null) {
                effective = original.clone();
                effective[1] = replacement;
            } else {
                XLog.d("rerender: cannot substitute, keeping original");
            }
        }

        RERENDERING.set(Boolean.TRUE);
        try {
            setMessageContent.invoke(contentView, effective);
        } catch (Throwable t) {
            XLog.w("rerender failed: " + t.getClass().getSimpleName(), t);
        } finally {
            RERENDERING.remove();
        }
    }

    // ---------------------------------------------------------------- 翻译流程

    /**
     * 消息绑定后的处理入口：识别英文、查缓存、按需发起翻译。
     */
    public void onMessageBound(@Nullable View contentView, String messageId,
                               @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        TextExtractor.Result ex = TextExtractor.extract(structurableText);
        if (ex == null || ex.text.trim().isEmpty()) {
            return;
        }

        Config.Snapshot cfg = config;
        String hash = Scheduler.contentHash(ex.text, cfg.version());
        StateStore.Entry entry = StateStore.get().obtain(messageId, hash, ex.text);
        rawContent.put(messageId, structurableText);
        if (contentView != null) {
            lastView.put(messageId, contentView);
        }

        if (!cfg.enabled) {
            // 总开关关闭：不发起新翻译，保留已有译文（需求 §2.7）。
            XLog.d("auto-translate disabled, skip " + messageId);
            return;
        }
        if (entry.status == StateStore.Status.TRANSLATING
                || entry.status == StateStore.Status.TRANSLATED) {
            return; // 已有在途任务或已有译文，不重复翻译（需求 §5.4 去重）。
        }

        // 缓存命中：直接出结果，不发请求（需求 §5.6）。
        String cached = TranslationCache.get().get(hash);
        if (cached != null) {
            entry.translatedText = cached;
            entry.status = StateStore.Status.TRANSLATED;
            entry.showingOriginal = false;
            XLog.d("cache hit for " + messageId);
            IconRegistry.refresh(messageId);
            RerenderRegistry.rerender(messageId);
            return;
        }

        if (!cfg.isUsable()) {
            entry.status = StateStore.Status.FAILED;
            entry.lastError = "API 未配置";
            XLog.d("api not configured, skip " + messageId);
            IconRegistry.refresh(messageId);
            return;
        }

        EnglishDetector.Verdict v = EnglishDetector.evaluate(
                ex.text, ex.textNodeCount, ex.opaqueNodeCount, ex.codeHeavy);
        if (!v.shouldTranslate) {
            XLog.d("skip " + messageId + " reason=" + v.reason);
            return;
        }

        entry.status = StateStore.Status.TRANSLATING;
        XLog.i("translate start " + messageId + " " + v.reason
                + " text=" + XLog.text(ex.text));

        Scheduler.get().submit(messageId, hash, ex.text, engine,
                (generationSnapshot, contentHash) -> {
                    if (generationSnapshot != StateStore.get().generation()) {
                        return false; // 模块已热重载/配置已重置：丢弃（需求 §风险 6）
                    }
                    if (!config.enabled) {
                        return false; // 开关已关闭：丢弃在途结果（需求 §2.7 注）
                    }
                    StateStore.Entry cur = StateStore.get().peek(messageId);
                    if (cur == null || !contentHash.equals(cur.contentHash)) {
                        return false; // 消息已被编辑：丢弃（需求 §风险 3）
                    }
                    return true;
                },
                (id, accepted, translated, error) ->
                        onTranslateResult(id, accepted, translated, error));
    }

    /** 翻译结果回写（主线程）。 */
    private void onTranslateResult(String id, boolean accepted,
                                   @Nullable String translated, @Nullable Throwable error) {
        StateStore.Entry cur = StateStore.get().peek(id);
        if (cur == null) {
            return;
        }
        if (!accepted) {
            // 被丢弃通常是过期或开关关闭：回到可重试状态，但不显示“失败”。
            if (cur.status == StateStore.Status.TRANSLATING) {
                cur.status = cur.translatedText != null
                        ? StateStore.Status.TRANSLATED
                        : StateStore.Status.IDLE;
            }
            IconRegistry.refresh(id);
            return;
        }
        if (error != null || translated == null || translated.isEmpty()) {
            cur.status = StateStore.Status.FAILED;
            cur.lastError = describe(error);
            XLog.w("translate failed " + id + ": " + cur.lastError);
        } else {
            cur.translatedText = translated;
            cur.status = StateStore.Status.TRANSLATED;
            cur.showingOriginal = false;
            // 写入缓存，供同内容的其它消息复用（需求 §5.6）。
            TranslationCache.get().put(cur.contentHash, translated);
            XLog.i("translate done " + id + " -> " + XLog.text(translated));
        }
        IconRegistry.refresh(id);
        RerenderRegistry.rerender(id);
    }

    /** 把异常转换为可读且不泄露密钥的描述。 */
    private static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof TranslationException) {
            return ((TranslationException) t).userMessage();
        }
        return t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** 原文/译文切换：由图标点击触发，不触发新请求（需求 §2.4）。 */
    public void toggleShowingOriginal(String messageId) {
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null || e.status != StateStore.Status.TRANSLATED || e.translatedText == null) {
            return;
        }
        e.showingOriginal = !e.showingOriginal;
        XLog.d("toggle " + messageId + " showingOriginal=" + e.showingOriginal);
        RerenderRegistry.rerender(messageId);
        IconRegistry.refresh(messageId);
    }

    /** 失败后重试：由图标点击触发（需求 §2.6）。 */
    public void retry(String messageId) {
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        e.status = StateStore.Status.IDLE;
        e.lastError = null;
        IconRegistry.refresh(messageId);

        Object structurable = rawContent.get(messageId);
        if (structurable == null) {
            XLog.d("retry skipped: no raw content for " + messageId);
            return;
        }
        onMessageBound(lastView.get(messageId), messageId, structurable);
    }

    // ---------------------------------------------------------------- 工具

    /** 判断字符串是否符合 Discord 雪花 ID 形态。 */
    public static boolean looksLikeSnowflake(@Nullable String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < SNOWFLAKE_MIN || n > SNOWFLAKE_MAX) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
