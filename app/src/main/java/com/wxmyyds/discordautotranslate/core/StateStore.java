package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模块实例代次与消息状态仓库。
 *
 * <p>热重载正确性（需求 §风险 6）依赖两点，本类负责其中两点：
 * <ol>
 *   <li><b>实例代次</b>：每次模块加载（含热重载后的新实例）分配新的 generation。
 *       异步任务在回写 UI 前必须校验 generation 仍为当前值，否则丢弃结果。</li>
 *   <li><b>内容版本</b>：每条消息用“内容哈希 + 翻译配置版本”作为版本键。
 *       编辑后哈希变化 → 旧译文立即失效，且旧请求的结果不会被接受。</li>
 * </ol>
 */
public final class StateStore {

    /** 单条消息的翻译状态。 */
    public enum Status {
        /** 尚未翻译。 */
        IDLE,
        /** 翻译进行中。 */
        TRANSLATING,
        /** 翻译成功，可显示译文。 */
        TRANSLATED,
        /** 翻译失败，可重试。 */
        FAILED
    }

    /** 单条消息的状态记录。 */
    public static final class Entry {
        public final String messageId;
        /** 原文内容哈希（含语言/配置版本），编辑后变化。 */
        public volatile String contentHash;
        /** 原文纯文本。 */
        public volatile String originalText;
        /** 译文，仅当 status == TRANSLATED 时有效。 */
        public volatile String translatedText;
        public volatile Status status = Status.IDLE;
        /** 用户当前是否正在查看原文（true=显示原文，false=显示译文）。 */
        public volatile boolean showingOriginal = false;
        /** 最近一次失败原因（用于诊断，不展示给用户）。 */
        public volatile String lastError;

        Entry(String messageId, String contentHash, String originalText) {
            this.messageId = messageId;
            this.contentHash = contentHash;
            this.originalText = originalText;
        }
    }

    private static final StateStore INSTANCE = new StateStore();

    public static StateStore get() {
        return INSTANCE;
    }

    /** 当前模块实例代次。热重载后递增，旧任务据此丢弃结果。 */
    private final AtomicInteger generation = new AtomicInteger(1);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 缓存上限，避免无限增长（需求 §5.6）。原型阶段取较小值便于观察淘汰。 */
    private static final int MAX_ENTRIES = 512;

    private StateStore() {
    }

    public int generation() {
        return generation.get();
    }

    /** 热重载：递增代次并清空全部状态，防止旧译文串入新实例。 */
    public void onNewGeneration() {
        generation.incrementAndGet();
        entries.clear();
        XLog.i("state store reset, generation=" + generation.get());
    }

    /**
     * 取得或创建某条消息的状态。若内容哈希变化（消息被编辑），
     * 立即失效旧译文（需求 §2.8）。
     */
    public Entry obtain(String messageId, String contentHash, String originalText) {
        Entry e = entries.get(messageId);
        if (e == null) {
            if (entries.size() >= MAX_ENTRIES) {
                evictSome();
            }
            Entry created = new Entry(messageId, contentHash, originalText);
            Entry prev = entries.putIfAbsent(messageId, created);
            e = prev != null ? prev : created;
        }
        synchronized (e) {
            if (!contentHash.equals(e.contentHash)) {
                XLog.d("content changed for " + messageId + " -> invalidate old translation");
                e.contentHash = contentHash;
                e.originalText = originalText;
                e.translatedText = null;
                e.status = Status.IDLE;
                e.showingOriginal = false;
                e.lastError = null;
            } else {
                e.originalText = originalText;
            }
        }
        return e;
    }

    @Nullable
    public Entry peek(String messageId) {
        return entries.get(messageId);
    }

    private void evictSome() {
        // 简单策略：先清理失败与空闲项。缓存策略在阶段 2 细化（需求 §5.6 要求上限）。
        int removed = 0;
        for (var it = entries.entrySet().iterator(); it.hasNext() && removed < MAX_ENTRIES / 4; ) {
            Entry e = it.next().getValue();
            if (e.status == Status.FAILED || e.status == Status.IDLE) {
                it.remove();
                removed++;
            }
        }
    }
}
