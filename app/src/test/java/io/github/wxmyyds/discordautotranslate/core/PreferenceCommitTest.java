package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure interface proxies: model Android committing memory before its disk result. */
public class PreferenceCommitTest {
    private static final String INITIALIZED = "settings_initialized";
    private static final String[] KEYS = {
            Config.KEY_API_URL, Config.KEY_API_KEY, Config.KEY_PROTOCOL,
            Config.KEY_ENABLED, Config.KEY_TIMEOUT_MS, "long", "float", "set",
            "absent", INITIALIZED
    };

    @Test
    public void failedResetRestoresEveryRawTypeAndAbsentInitializationFlag() {
        FakePrefs fake = savedValues();
        Map<String, Object> before = new HashMap<>(fake.memory);
        fake.outcomes.add(false);
        fake.outcomes.add(true);

        PreferenceCommit.Result result = reset(fake);

        assertFalse(result.committed);
        assertNull(result.failure);
        assertFalse(result.rollbackFailed);
        assertEquals(before, fake.memory);
        assertEquals(before, fake.disk);
        assertFalse(fake.memory.containsKey(INITIALIZED));
        assertFalse(fake.memory.containsKey("absent"));
        assertEquals(2, fake.commits);
        assertEquals(1, fake.editors);
        assertFalse(PreferenceCommit.isPoisoned(fake.prefs));
    }

    @Test
    public void throwingCommitRestoresMemoryAndRetainsOriginalFailure() {
        FakePrefs fake = savedValues();
        fake.memory.put(INITIALIZED, false);
        Map<String, Object> before = new HashMap<>(fake.memory);
        RuntimeException initialFailure = new IllegalStateException();
        fake.outcomes.add(initialFailure);
        fake.outcomes.add(true);

        PreferenceCommit.Result result = reset(fake);

        assertFalse(result.committed);
        assertSame(initialFailure, result.failure);
        assertFalse(result.rollbackFailed);
        assertEquals(before, fake.memory);
        assertEquals(Boolean.FALSE, fake.memory.get(INITIALIZED));
        assertEquals(2, fake.commits);
        assertEquals(1, fake.editors);
    }

    @Test
    public void successfulSaveUsesOneCommitAndDoesNotRestoreOldValues() {
        FakePrefs fake = savedValues();
        fake.outcomes.add(true);

        PreferenceCommit.Result result = PreferenceCommit.commit(fake.prefs, fake.prefs.edit(),
                KEYS, editor -> editor.putString(Config.KEY_API_URL, "https://new.example/v1")
                        .remove(Config.KEY_API_KEY).putBoolean(INITIALIZED, true));

        assertTrue(result.committed);
        assertNull(result.failure);
        assertFalse(result.rollbackFailed);
        assertEquals("https://new.example/v1", fake.memory.get(Config.KEY_API_URL));
        assertFalse(fake.memory.containsKey(Config.KEY_API_KEY));
        assertEquals(Boolean.TRUE, fake.memory.get(INITIALIZED));
        assertEquals(fake.memory, fake.disk);
        assertEquals(1, fake.commits);
        assertFalse(PreferenceCommit.isPoisoned(fake.prefs));
    }

    @Test
    public void failedSaveRestoresRemovedKeysNotDefaults() {
        FakePrefs fake = savedValues();
        fake.memory.put(INITIALIZED, true);
        Map<String, Object> before = new HashMap<>(fake.memory);
        fake.outcomes.add(false);
        fake.outcomes.add(true);

        PreferenceCommit.Result result = PreferenceCommit.commit(fake.prefs, fake.prefs.edit(),
                KEYS, editor -> editor.remove(Config.KEY_API_URL)
                        .remove(Config.KEY_API_KEY).putBoolean(INITIALIZED, true));

        assertFalse(result.committed);
        assertEquals(before, fake.memory);
        assertEquals(Boolean.TRUE, fake.memory.get(INITIALIZED));
        assertEquals(2, fake.commits);
    }

    @Test
    public void failedRollbackDiskStillRestoresMemoryButRequiresExplicitRecovery() {
        FakePrefs fake = savedValues();
        Map<String, Object> before = new HashMap<>(fake.memory);
        fake.outcomes.add(false);
        fake.outcomes.add(false);

        PreferenceCommit.Result result = reset(fake);

        assertFalse(result.committed);
        assertTrue(result.rollbackFailed);
        assertNull(result.rollbackFailure);
        assertEquals(before, fake.memory);
        assertEquals(2, fake.commits);
        assertTrue(PreferenceCommit.isPoisoned(fake.prefs));

        fake.outcomes.add(true);
        assertTrue(reset(fake).committed);
        assertFalse(PreferenceCommit.isPoisoned(fake.prefs));
        assertEquals(3, fake.commits);
    }

    @Test
    public void throwingRollbackCommitReportsBothFailuresAndRestoresMemory() {
        FakePrefs fake = savedValues();
        Map<String, Object> before = new HashMap<>(fake.memory);
        RuntimeException initialFailure = new IllegalStateException();
        RuntimeException rollbackFailure = new IllegalArgumentException();
        fake.outcomes.add(initialFailure);
        fake.outcomes.add(rollbackFailure);

        PreferenceCommit.Result result = reset(fake);

        assertFalse(result.committed);
        assertSame(initialFailure, result.failure);
        assertTrue(result.rollbackFailed);
        assertSame(rollbackFailure, result.rollbackFailure);
        assertEquals(before, fake.memory);
        assertEquals(2, fake.commits);
        assertTrue(PreferenceCommit.isPoisoned(fake.prefs));
    }

    @Test
    public void restorationStagingThrowRetainsOriginalSnapshotAcrossActivityRetry() {
        FakePrefs fake = savedValues();
        Map<String, Object> before = new HashMap<>(fake.memory);
        fake.outcomes.add(false);
        fake.throwOnRestorePut = true;

        PreferenceCommit.Result result = reset(fake);

        assertFalse(result.committed);
        assertTrue(result.rollbackFailed);
        assertTrue(result.rollbackFailure instanceof IllegalStateException);
        assertFalse(fake.memory.containsKey(Config.KEY_API_URL));
        assertTrue(PreferenceCommit.isPoisoned(fake.prefs));
        assertEquals(1, fake.commits);

        // A later activity gets the same process-local preferences instance. Its failed
        // explicit reset must restore the FIRST snapshot, not the now-erased map.
        fake.throwOnRestorePut = false;
        fake.outcomes.add(false);
        fake.outcomes.add(true);
        PreferenceCommit.Result retry = reset(fake);
        assertFalse(retry.committed);
        assertEquals(before, fake.memory);
        assertFalse(PreferenceCommit.isPoisoned(fake.prefs));
        assertEquals(3, fake.commits);

        fake.outcomes.add(true);
        assertTrue(reset(fake).committed);
        assertEquals(Boolean.TRUE, fake.memory.get(INITIALIZED));
        assertFalse(fake.memory.containsKey(Config.KEY_API_URL));
    }

    @Test
    public void stringSetSnapshotDoesNotAliasSourceSet() {
        FakePrefs fake = savedValues();
        Set<String> expected = new HashSet<>(Arrays.asList("first", "second"));
        fake.outcomes.add(false);
        fake.outcomes.add(true);

        PreferenceCommit.Result result = PreferenceCommit.commit(fake.prefs, fake.prefs.edit(),
                KEYS, editor -> {
                    ((Set<?>) fake.memory.get("set")).clear();
                    editor.remove("set");
                });

        assertFalse(result.committed);
        assertEquals(expected, fake.memory.get("set"));
    }

    @Test
    public void unknownTypeFailsBeforeAnyEditorMutationOrCommit() {
        assertUnknownTypeUntouched(new Object());
        assertUnknownTypeUntouched(null);
        assertUnknownTypeUntouched(new HashSet<>(Arrays.asList("known", 17)));
    }

    private static void assertUnknownTypeUntouched(Object value) {
        FakePrefs fake = savedValues();
        fake.memory.put(Config.KEY_API_KEY, value);
        Map<String, Object> before = new HashMap<>(fake.memory);
        boolean[] invoked = {false};

        PreferenceCommit.Result result = PreferenceCommit.commit(fake.prefs, fake.prefs.edit(),
                KEYS, editor -> {
                    invoked[0] = true;
                    editor.remove(Config.KEY_API_URL);
                });

        assertFalse(result.committed);
        assertTrue(result.failure instanceof IllegalArgumentException);
        assertFalse(result.rollbackFailed);
        assertFalse(invoked[0]);
        assertEquals(before, fake.memory);
        assertEquals(0, fake.mutations);
        assertEquals(0, fake.commits);
        assertFalse(PreferenceCommit.isPoisoned(fake.prefs));
    }

    private static PreferenceCommit.Result reset(FakePrefs fake) {
        return PreferenceCommit.commit(fake.prefs, fake.prefs.edit(), KEYS, editor -> {
            for (String key : KEYS) {
                editor.remove(key);
            }
            editor.putBoolean(INITIALIZED, true);
        });
    }

    private static FakePrefs savedValues() {
        FakePrefs fake = new FakePrefs();
        fake.memory.put(Config.KEY_API_URL, "https://saved.example/v1");
        fake.memory.put(Config.KEY_API_KEY, "saved-value");
        fake.memory.put(Config.KEY_PROTOCOL, "invalid-protocol");
        fake.memory.put(Config.KEY_ENABLED, false);
        fake.memory.put(Config.KEY_TIMEOUT_MS, 45000);
        fake.memory.put("long", 19L);
        fake.memory.put("float", 0.25f);
        fake.memory.put("set", new HashSet<>(Arrays.asList("first", "second")));
        fake.memory.put("untouched", "leave-alone");
        fake.disk.putAll(fake.memory);
        return fake;
    }

    private static final class FakePrefs {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        final Deque<Object> outcomes = new ArrayDeque<>();
        int commits;
        int editors;
        int mutations;
        boolean throwOnRestorePut;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll":
                            return new HashMap<>(memory);
                        case "edit":
                            editors++;
                            return editor();
                        default:
                            throw new AssertionError("Unexpected preferences method");
                    }
                });

        SharedPreferences.Editor editor() {
            Map<String, Object> staged = new HashMap<>();
            Object removed = new Object();
            boolean[] committedOnce = {false};
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("commit")) {
                            commits++;
                            committedOnce[0] = true;
                            for (Map.Entry<String, Object> entry : staged.entrySet()) {
                                if (entry.getValue() == removed) {
                                    memory.remove(entry.getKey());
                                } else {
                                    memory.put(entry.getKey(), entry.getValue());
                                }
                            }
                            staged.clear();
                            Object outcome = outcomes.removeFirst();
                            if (outcome instanceof RuntimeException) {
                                throw (RuntimeException) outcome;
                            }
                            if (Boolean.TRUE.equals(outcome)) {
                                disk.clear();
                                disk.putAll(memory);
                            }
                            return outcome;
                        }
                        if (name.equals("remove")) {
                            mutations++;
                            staged.put((String) args[0], removed);
                            return proxy;
                        }
                        if (name.startsWith("put")) {
                            if (throwOnRestorePut && committedOnce[0]) {
                                throw new IllegalStateException();
                            }
                            mutations++;
                            Object value = args[1];
                            if (value instanceof Set<?>) {
                                value = new HashSet<>((Set<?>) value);
                            }
                            staged.put((String) args[0], value);
                            return proxy;
                        }
                        throw new AssertionError("Unexpected editor method");
                    });
        }
    }
}
