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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染层与翻译状态之间的桥。
 *
 * <p>职责：
 * <ol>
 *   <li>维护 <b>正文视图 → 消息 ID</b> 与 <b>内容对象 → 消息 ID</b> 两张映射。
 *       必须如此：RecyclerView 会复用 {@code MessageContentView} 实例，
 *       译文状态绝不能存在 View 上（需求 §风险 2）。</li>
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

    /** 正文视图 → 消息 ID（有界，避免列表回收后无限增长）。 */
    private final Map<View, String> viewToMessageId = boundedMap(512);

    /** 消息 ID → 原始 StructurableText（有界，供失败重试使用）。 */
    private final Map<String, Object> rawContent = boundedMap(512);

    /** 消息 ID → 最近承载它的视图（弱引用，避免消息状态反向固定 View）。 */
    private final Map<String, java.lang.ref.WeakReference<View>> lastView = boundedMap(512);

    /** 按引用身份反查消息 ID；Discord StructurableText 的 equals() 是按内容比较。 */
    private final IdentityMessageMap contentIdentityToMessageId = new IdentityMessageMap();

    /**
     * 仅用于图标注入的内容身份映射（键 ← 内容实例）。
     *
     * <p>帖子预览的标题是普通 String、不经 toSpannable；图标改挂在卡片内唯一走
     * toSpannable 的 footer 上。footer 不能进入替换身份映射（否则译文会错误地
     * 写进 footer），因此单独一张表，只被图标注入器读取。</p>
     */
    private final IdentityMessageMap iconOnlyContentToKey = new IdentityMessageMap();

    /** 允许注入行内图标的 Embed 状态键：每张卡片只标一段，避免逐段各挂一个图标。 */
    private final java.util.Set<String> embedIconKeys =
            java.util.Collections.newSetFromMap(boundedMap(1024));

    private volatile Config.Snapshot config = Config.read(null);
    private volatile Scheduler.Engine engine = new JsonApiEngine(config);

    private HookBridge() {
    }

    // ---------------------------------------------------------------- 配置与引擎

    /**
     * 更新配置。会影响翻译结果的配置变化（URL/模型/目标语言/协议/提示词/凭据）
     * 发生后，递增状态代次使旧译文与在途任务失效（需求 §5.6、§5.7）。
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

        // version() 已包含密钥/额外请求头指纹：凭据变更同样会使缓存与在途结果失效。
        if (old == null || !old.version().equals(c.version())) {
            XLog.i("translation config changed, invalidating cached state");
            StateStore.get().onNewGeneration();
            TranslationCache.get().clear();
        }
        // 引擎随配置重建，保证下一次请求使用最新配置。
        this.engine = new JsonApiEngine(c);
    }

    // ---------------------------------------------------------------- 视图映射

    /** 记录正文视图当前绑定的消息 ID。列表复用时会被覆盖，始终反映最新绑定。 */
    public void bindView(View contentView, String messageId) {
        if (contentView == null || messageId == null) {
            return;
        }
        String prev = viewToMessageId.put(contentView, messageId);
        if (prev != null && !prev.equals(messageId)) {
            java.lang.ref.WeakReference<View> last = lastView.get(prev);
            if (last != null && last.get() == contentView) {
                lastView.remove(prev);
            }
        }
        lastView.put(messageId, new java.lang.ref.WeakReference<>(contentView));
    }

    @Nullable
    public String messageIdOf(@Nullable View contentView) {
        return contentView == null ? null : viewToMessageId.get(contentView);
    }

    /** 清空全部映射。热重载与配置重置时调用。 */
    public void reset() {
        viewToMessageId.clear();
        rawContent.clear();
        lastView.clear();
        contentIdentityToMessageId.clear();
        iconOnlyContentToKey.clear();
        embedIconKeys.clear();
    }

    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    // ------------------------------------------------------- 内容 → 消息 ID

    /**
     * 记录“内容对象 → 消息 ID”。只能在能拿到权威消息 ID 的地方调用
     * （目前是 {@code MessageContentViewHolder.bind} 与 {@code configureReply}）。
     */
    public void rememberContent(String messageId, @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        contentIdentityToMessageId.put(structurableText, messageId);
    }

    /**
     * 反查某个 {@code StructurableText} 属于哪条消息。
     *
     * <p>只按对象身份映射反查。不得按正文哈希兜底：不同消息完全可能有相同正文，
     * 将哈希映射到单个 ID 会把译文错误地套用到另一条消息。</p>
     */
    @Nullable
    public String resolveMessageId(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        return contentIdentityToMessageId.get(structurableText);
    }

    // ------------------------------------------------------- Embed 状态键

    /**
     * 为 Embed 文本生成独立的状态键。
     *
     * <p>格式：{@code 行消息ID + "\u0000embed\u0000" + 内容哈希}。它与正文共用
     * 同一套翻译管线（StateStore/Scheduler/缓存/重渲染/替换），但：
     * <ul>
     *   <li>与正文的状态条目、与同消息内不同 Embed 文本（标题/描述/字段）
     *       互不覆盖——每段文本按内容哈希各占一条；</li>
     *   <li>哈希与 {@link #onMessageBound} 完全同口径，保证键与条目一致；</li>
     *   <li>分隔符含 {@code U+0000}，真实雪花 ID 不可能包含，可安全区分
     *       Embed 键与正文键。</li>
     * </ul>
     *
     * @return 状态键；文本为空或类型未知时返回 null（调用方应跳过）
     */
    @Nullable
    public String embedStateKey(String rowMessageId, @Nullable Object structurable) {
        TextExtractor.Result ex = TextExtractor.extract(structurable);
        if (ex == null || ex.text.trim().isEmpty()) {
            return null;
        }
        return stateKey(rowMessageId, contentHashOf(ex, config));
    }

    /**
     * 按裸文本生成 Embed 状态键（用于不经 StructurableText 渲染的文本，
     * 例如原生帖子预览的标题）。
     *
     * <p>与 {@link #embedStateKey} 的约定：同一段纯文本两者生成的键<b>必须相等</b>
     * （登记侧与替换侧各用其一），单测锁定该不变量。</p>
     */
    @Nullable
    public String embedTextStateKey(String rowMessageId, @Nullable String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return null;
        }
        return stateKey(rowMessageId, contentHashOf(rawText, config));
    }

    /** 是否为 Embed 状态键。 */
    public static boolean isEmbedKey(@Nullable String key) {
        return key != null && key.contains(EMBED_KEY_PREFIX);
    }

    /**
     * 取 Embed 状态键中的行消息 ID 部分；非 Embed 键原样返回。
     *
     * <p>同一个 Embed 视图承载同一条消息的多段文本（标题/描述/字段），
     * 各段键不同但行 ID 相同——复用校验按“行未变即仍有效”放宽，
     * 行 ID 变化（视图被复用到其他消息）则仍然拒绝。</p>
     */
    public static String embedRowOf(@Nullable String key) {
        if (key == null) {
            return key;
        }
        int i = key.indexOf(EMBED_KEY_PREFIX);
        return i >= 0 ? key.substring(0, i) : key;
    }

    /** 标记某 Embed 状态键为所在卡片的图标承载段（每张卡片只标一段）。 */
    public void markEmbedIconKey(String key) {
        if (key != null) {
            embedIconKeys.add(key);
        }
    }

    /** 该 Embed 状态键当前是否允许注入行内图标。 */
    public boolean isEmbedIconKey(@Nullable String key) {
        return key != null && embedIconKeys.contains(key);
    }

    /**
     * 登记「仅用于图标」的内容身份：该内容实例反查出的键只驱动图标注入，
     * 不参与 toSpannable 的译文替换（替换仍走 {@link #resolveMessageId}）。
     */
    public void rememberIconOnlyContent(String key, @Nullable Object content) {
        if (key == null || content == null) {
            return;
        }
        iconOnlyContentToKey.put(content, key);
    }

    /** 反查仅图标身份映射；未登记返回 null。 */
    @Nullable
    public String resolveIconOnlyKey(@Nullable Object content) {
        if (content == null) {
            return null;
        }
        return iconOnlyContentToKey.get(content);
    }

    private static String stateKey(String rowMessageId, String contentHash) {
        return rowMessageId + EMBED_KEY_PREFIX + contentHash;
    }

    /** 与 {@link #onMessageBound} 同口径的内容哈希（含分段标记协议版本）。 */
    private static String contentHashOf(TextExtractor.Result ex, Config.Snapshot cfg) {
        String hashInput = ex.segmented != null ? ex.text + SEGMENT_CACHE_SUFFIX : ex.text;
        return Scheduler.contentHash(hashInput, cfg.version());
    }

    /** 裸文本的内容哈希：与 PrimitiveStructurableText 提取出的纯文本同口径。 */
    private static String contentHashOf(String rawText, Config.Snapshot cfg) {
        return Scheduler.contentHash(rawText, cfg.version());
    }

    // ---------------------------------------------------------------- 渲染拦截

    /** 当前是否处于“重渲染”调用中。 */
    public boolean isRerendering() {
        return Boolean.TRUE.equals(RERENDERING.get());
    }

    /**
     * 记录“内容对象 → 消息 ID”身份映射。
     *
     * <p>阶段 3 修正：正文替换统一在渲染漏斗 {@code TextUtilsKt.toSpannable} 完成。
     * 重渲染使用 {@link #requestRerender} 的实参，不再在此保存。
     *
     * @return 始终返回传入的同一数组
     */
    public Object[] interceptRender(View contentView, Object[] args,
                                    @Nullable Object structurableText) {
        if (contentView != null && args != null && structurableText != null) {
            String id = viewToMessageId.get(contentView);
            if (id != null) {
                rememberContent(id, structurableText);
            }
        }
        return args;
    }

    /**
     * 投递重渲染：必须离开当前宿主布局过程再执行。
     *
     * <p>调用方（{@code onMessageBound}、点击回调）可能正处于 RecyclerView 的
     * measure/layout 中，此时直接重渲染会让新的布局请求被本轮遍历丢掉，
     * 表现为“文字变了但行高/空白行要等滚动才更新”。投递到视图消息队列后，
     * 下一次遍历再测量，结果立即生效。</p>
     *
     * <p>视图是否已被复用的判定见 {@link RerenderRegistry}；投递执行时由
     * {@code toSpannable} 按当前状态重新决定替换与否，因此不会把译文写错消息。</p>
     *
     * <p>真正的文本替换发生在 {@code TextUtilsKt.toSpannable}，
     * 它会按当前状态自行决定是否替换。</p>
     *
     * <p>排队期间视图可能已被 RecyclerView 复用到其他消息，因此执行前必须
     * 再校验一次：绑定已变成其他消息则跳过，不能把旧译文渲染到新消息上。
     * 绑定未知（{@code null}，通常是映射表 LRU 淘汰）不视为复用。</p>
     *
     * @param contentView 用于校验复用的正文视图，可为 null（不校验）
     * @param messageId   期望绑定的消息 ID，可为 null（不校验）
     * @param invocationTarget 调用 {@code method} 的接收者（正文视图或回复预览容器）
     */
    public void requestRerender(@Nullable View contentView, @Nullable String messageId,
                                @Nullable Object invocationTarget, @Nullable Method method,
                                @Nullable Object[] args) {
        if (invocationTarget == null || method == null || args == null) {
            return;
        }
        String bound = messageIdOf(contentView);
        if (contentView != null && messageId != null && !RerenderRegistry.stillBound(messageId, bound)) {
            XLog.d("rerender skipped: view rebound to another message");
            return;
        }
        Runnable task = () -> {
            String currentBound = messageIdOf(contentView);
            if (contentView != null && messageId != null
                    && !RerenderRegistry.stillBound(messageId, currentBound)) {
                return;
            }
            invokeRerender(invocationTarget, contentView, messageId, method, args);
        };
        if (invocationTarget instanceof View) {
            View host = (View) invocationTarget;
            // View.post 把执行移出当前布局遍历；视图未附着时排队到下一次 attach。
            if (!host.post(task)) {
                task.run();
            }
            return;
        }
        // 非视图目标（理论上不出现）：没有可用的布局时机，保持原同步行为。
        task.run();
    }

    private void invokeRerender(Object invocationTarget, @Nullable View contentView,
                                @Nullable String messageId, Method method, Object[] args) {
        View view = contentView != null
                ? contentView
                : (invocationTarget instanceof View ? (View) invocationTarget : null);
        RERENDERING.set(Boolean.TRUE);
        try {
            method.invoke(invocationTarget, args);
        } catch (Throwable t) {
            XLog.w("rerender failed: " + t.getClass().getSimpleName(), t);
            return;
        } finally {
            RERENDERING.remove();
        }
        // 回复引用的调用目标是容器，真正需要重新绑定的是内部正文视图。
        RowRefresher.postInvalidateRow(this::messageIdOf, messageId, view);
    }

    /**
     * 启用分段标记时附加到缓存键的后缀。
     *
     * <p>含 {@code U+0000} 保证不会与任何真实正文混淆；只有存在 ≥2 个文本节点
     * 的消息会带上它，因此单节点消息的缓存与旧版本保持兼容。</p>
     */
    private static final String SEGMENT_CACHE_SUFFIX = "\u0000seg1";

    /** Embed 状态键的分隔符；真实消息 ID（雪花 ID）不可能包含 U+0000。 */
    private static final String EMBED_KEY_PREFIX = "\u0000embed\u0000";

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
        // 请求文本带分段标记（存在 ≥2 个文本节点时），使译文能逐段回填。
        // 内容哈希仍以无标记纯文本为主：它决定「内容是否相同」。
        // 但对启用标记的消息追加一个协议版本后缀：这类消息的译文形态变了
        // （从「整段塞进首节点」变成「逐段带标记」），旧缓存条目必须失效，
        // 否则已缓存的旧译文会继续走回退路径、格式照旧丢失。
        // 单节点消息不受影响，其缓存与旧版本完全兼容。
        String requestText = ex.segmented != null ? ex.segmented : ex.text;
        String hash = contentHashOf(ex, cfg);
        StateStore.Entry entry = StateStore.get().obtain(messageId, hash, ex.text);
        rawContent.put(messageId, structurableText);
        if (contentView != null) {
            lastView.put(messageId, new java.lang.ref.WeakReference<>(contentView));
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
        if (entry.status == StateStore.Status.FAILED) {
            // 失败后不自动重试：渲染漏斗会被频繁调用（每次重绘都会经过），
            // 若无条件重试会造成请求风暴。失败后统一由用户点图标重试（需求 §2.6）。
            return;
        }

        // 缓存命中：直接出结果，不发请求（需求 §5.6）。
        String cached = TranslationCache.get().get(hash);
        if (cached != null) {
            entry.translatedText = cached;
            entry.status = StateStore.Status.TRANSLATED;
            entry.showingOriginal = false;
            XLog.d("cache hit for " + messageId);
            RerenderRegistry.rerender(messageId);
            return;
        }

        if (!cfg.isUsable()) {
            entry.status = StateStore.Status.FAILED;
            entry.lastError = "API 未配置";
            XLog.d("api not configured, skip " + messageId);
            RerenderRegistry.rerender(messageId);
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
        // 立即重渲染，让「翻译中」旋转图标显示出来。
        RerenderRegistry.rerender(messageId);

        Scheduler.get().submit(messageId, hash, requestText, engine,
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
            RerenderRegistry.rerender(id);
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
    }

    /**
     * 行内图标点击的统一入口（需求：成功时切换原文/译文，失败时重试）。
     *
     * <p>由 {@code IconClickSpanFactory} 创建的 span 回调触发。
     * 状态可能因列表复用而变化，因此每次都重新读取当前状态。</p>
     */
    public void onIconClicked(String messageId) {
        if (messageId == null) {
            return;
        }
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        switch (e.status) {
            case TRANSLATED:
                if (e.translatedText != null) {
                    toggleShowingOriginal(messageId);
                }
                break;
            case FAILED:
                retry(messageId);
                break;
            default:
                // 翻译中或未开始：忽略，避免重复触发。
                break;
        }
    }

    /** 失败后重试：由图标点击触发（需求 §2.6）。 */
    public void retry(String messageId) {
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        Object structurable = rawContent.get(messageId);
        if (structurable == null) {
            XLog.d("retry skipped: no raw content for " + messageId);
            return;
        }

        e.status = StateStore.Status.IDLE;
        e.lastError = null;
        RerenderRegistry.rerender(messageId);

        java.lang.ref.WeakReference<View> viewRef = lastView.get(messageId);
        View last = viewRef == null ? null : viewRef.get();
        onMessageBound(last, messageId, structurable);
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
