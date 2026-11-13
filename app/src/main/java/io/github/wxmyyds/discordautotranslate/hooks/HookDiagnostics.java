package io.github.wxmyyds.discordautotranslate.hooks;

import android.util.Log;

import io.github.wxmyyds.discordautotranslate.core.ModuleResources;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Low-volume, content-free diagnostics for version-specific hook activation. */
public final class HookDiagnostics {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private HookDiagnostics() {
    }

    public static void infoOnce(String key, String message) {
        if (REPORTED.add(key)) {
            Log.i("DiscordAutoTranslate", message);
            ModuleResources.logDiagnostic(Log.INFO, message);
        }
    }

    public static void warnOnce(String key, String message) {
        if (REPORTED.add(key)) {
            Log.w("DiscordAutoTranslate", message);
            ModuleResources.logDiagnostic(Log.WARN, message);
        }
    }
}