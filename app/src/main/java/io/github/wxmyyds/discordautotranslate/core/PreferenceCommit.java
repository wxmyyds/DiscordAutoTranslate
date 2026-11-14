package io.github.wxmyyds.discordautotranslate.core;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Local preference transaction rollback, including Android's memory-before-disk commits. */
public final class PreferenceCommit {
    private PreferenceCommit() {}

    // Android returns the same local SharedPreferences instance throughout a process.
    // Retain the original snapshot until an explicit transaction or its rollback succeeds.
    private static final Map<SharedPreferences, Snapshot> POISONED = new IdentityHashMap<>();

    public interface Mutation {
        /** Only the declared touched keys may be changed; never call clear/apply/commit. */
        void write(SharedPreferences.Editor editor);
    }

    public static final class Result {
        public final boolean committed;
        public final Throwable failure;
        public final boolean rollbackFailed;
        public final Throwable rollbackFailure;

        private Result(boolean committed, Throwable failure,
                       boolean rollbackFailed, Throwable rollbackFailure) {
            this.committed = committed;
            this.failure = failure;
            this.rollbackFailed = rollbackFailed;
            this.rollbackFailure = rollbackFailure;
        }
    }

    public static synchronized boolean isPoisoned(SharedPreferences prefs) {
        return POISONED.containsKey(prefs);
    }

    /**
     * Used only for explicit saves/resets (or initial import on a healthy store).
     * Capture and validate every raw value before any editor mutation. A failed original
     * commit is always a failed result, even if restoring the snapshot succeeds.
     * Callers reading/syncing the store must hold this class's monitor and check poison.
     */
    public static synchronized Result commit(SharedPreferences prefs,
                                             SharedPreferences.Editor editor,
                                             String[] touchedKeys, Mutation mutation) {
        final Snapshot original;
        try {
            Snapshot retained = POISONED.get(prefs);
            if (retained != null && !Arrays.equals(retained.keys, touchedKeys)) {
                throw new IllegalArgumentException("Transaction keys changed during recovery");
            }
            original = retained == null ? new Snapshot(prefs, touchedKeys) : retained;
        } catch (Throwable failure) {
            return new Result(false, failure, false, null);
        }

        Throwable failure = null;
        try {
            mutation.write(editor);
            if (editor.commit()) {
                POISONED.remove(prefs);
                return new Result(true, null, false, null);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        }

        // Install the guard before even staging restoration: a put/remove can throw too.
        POISONED.put(prefs, original);
        try {
            original.restore(editor);
            // Exactly one restoration commit, never a retry of the failed mutation.
            if (editor.commit()) {
                POISONED.remove(prefs);
                return new Result(false, failure, false, null);
            }
            // Android has restored memory, but disk is still unconfirmed. Require an
            // explicit reset/retry rather than loading/syncing uncertain persistence.
            return new Result(false, failure, true, null);
        } catch (Throwable rollbackFailure) {
            return new Result(false, failure, true, rollbackFailure);
        }
    }

    private static final class Snapshot {
        final String[] keys;
        final Map<String, Object> values = new LinkedHashMap<>();

        Snapshot(SharedPreferences prefs, String[] touchedKeys) {
            keys = touchedKeys.clone();
            Map<String, ?> raw = prefs.getAll();
            for (String key : keys) {
                if (raw.containsKey(key)) {
                    Object value = raw.get(key);
                    if (value instanceof Set<?>) {
                        Set<String> copy = new HashSet<>();
                        for (Object item : (Set<?>) value) {
                            if (!(item instanceof String)) {
                                throw new IllegalArgumentException("Unsupported preference type");
                            }
                            copy.add((String) item);
                        }
                        value = copy;
                    } else if (!(value instanceof String || value instanceof Boolean
                            || value instanceof Integer || value instanceof Long
                            || value instanceof Float)) {
                        // A present null cannot be represented exactly by an Android Editor.
                        throw new IllegalArgumentException("Unsupported preference type");
                    }
                    values.put(key, value);
                }
            }
        }

        @SuppressWarnings("unchecked")
        void restore(SharedPreferences.Editor editor) {
            for (String key : keys) {
                if (!values.containsKey(key)) {
                    editor.remove(key);
                    continue;
                }
                Object value = values.get(key);
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                } else {
                    editor.putStringSet(key, new HashSet<>((Set<String>) value));
                }
            }
        }
    }
}
