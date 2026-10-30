package io.github.wxmyyds.discordautotranslate.core;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内存版 {@link SharedPreferences}。
 *
 * <p>用途：在不改动持久化的情况下，用当前界面上的输入值构造一份
 * {@link Config.Snapshot} 做“配置检查 / 测试翻译”，
 * 使测试行为与保存后的运行行为完全一致（共用同一套默认值与读取逻辑）。</p>
 */
public final class MemoryPrefs implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();

    public MemoryPrefs put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    @Override
    public Map<String, ?> getAll() {
        return Collections.unmodifiableMap(values);
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        Object v = values.get(key);
        if (v == null) return defValue;
        if (!(v instanceof String)) throw new ClassCastException(key + " is not a String");
        return (String) v;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object v = values.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        if (v == null) return defValue;
        if (!(v instanceof Integer)) throw new ClassCastException(key + " is not an Integer");
        return (Integer) v;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        if (v == null) return defValue;
        if (!(v instanceof Long)) throw new ClassCastException(key + " is not a Long");
        return (Long) v;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = values.get(key);
        if (v == null) return defValue;
        if (!(v instanceof Float)) throw new ClassCastException(key + " is not a Float");
        return (Float) v;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        if (v == null) return defValue;
        if (!(v instanceof Boolean)) throw new ClassCastException(key + " is not a Boolean");
        return (Boolean) v;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /**
     * 本类只用于构造只读快照（{@link Config#read}），不承载持久化编辑；
     * 与旧 {@code Config.Defaults} 一致，调用 {@code edit()} 视为编程错误。
     */
    @Override
    public Editor edit() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // 内存实现无需通知。
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // 内存实现无需通知。
    }
}
