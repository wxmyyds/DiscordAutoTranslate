package com.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 翻译缓存（需求 §5.6）。
 *
 * <p>键为 {@link Scheduler#contentHash(String, String)} 的结果，即
 * {@code SHA-256(正文 + 配置版本)}。配置版本包含 API URL、模型名、目标语言、
 * 提取路径与请求模板，因此：
 * <ul>
 *   <li>同一内容重复出现（滚动回看、重复进入频道）时直接复用，减少请求与费用。</li>
 *   <li>任一影响结果的配置变化后，旧条目自然不再命中，不会错误复用。</li>
 *   <li>消息编辑后正文变化 → 哈希变化 → 不会命中旧译文。</li>
 * </ul>
 *
 * <p>容量有上限（LRU 淘汰），避免无限增长。</p>
 *
 * <p>线程安全：所有访问都加锁。翻译结果回写发生在主线程，读取可能发生在渲染路径，
 * 但两者都不在锁内做耗时操作。</p>
 */
public final class TranslationCache {

    /** 缓存条目上限。 */
    private static final int MAX_ENTRIES = 512;

    private static volatile TranslationCache sInstance;

    public static TranslationCache get() {
        TranslationCache c = sInstance;
        if (c == null) {
            synchronized (TranslationCache.class) {
                c = sInstance;
                if (c == null) {
                    c = new TranslationCache(MAX_ENTRIES);
                    sInstance = c;
                }
            }
        }
        return c;
    }

    /** 热重载：丢弃缓存，避免旧代次的对象图被保留。 */
    public static void reset() {
        synchronized (TranslationCache.class) {
            sInstance = null;
        }
    }

    private final int maxEntries;
    private final LinkedHashMap<String, String> lru;

    // 统计，供诊断日志使用
    private long hits;
    private long misses;
    private long evictions;

    TranslationCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        // accessOrder=true 实现 LRU
        this.lru = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() > TranslationCache.this.maxEntries) {
                    evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    @Nullable
    public synchronized String get(String key) {
        String v = lru.get(key);
        if (v != null) {
            hits++;
        } else {
            misses++;
        }
        return v;
    }

    public synchronized void put(String key, String translated) {
        if (key == null || translated == null || translated.isEmpty()) {
            return;
        }
        lru.put(key, translated);
    }

    public synchronized int size() {
        return lru.size();
    }

    public synchronized void clear() {
        lru.clear();
        hits = 0;
        misses = 0;
        evictions = 0;
    }

    /** 诊断摘要：命中/未命中/淘汰计数。 */
    public synchronized String stats() {
        return "cache[size=" + lru.size() + "/" + maxEntries
                + ", hits=" + hits + ", misses=" + misses
                + ", evictions=" + evictions + "]";
    }
}
