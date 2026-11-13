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

    public static void info(String message) {
        Log.i("DiscordAutoTranslate", message);
        ModuleResources.logDiagnostic(Log.INFO, message);
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

    /** Host class and method names only; never logs arguments or message contents. */
    public static String discordCallers() {
        StringBuilder callers = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (!className.startsWith("com.discord.")) {
                continue;
            }
            if (callers.length() > 0) {
                callers.append(" <- ");
            }
            callers.append(className).append('#').append(frame.getMethodName());
            if (callers.length() >= 700) {
                break;
            }
        }
        return callers.length() == 0 ? "none" : callers.toString();
    }
}
