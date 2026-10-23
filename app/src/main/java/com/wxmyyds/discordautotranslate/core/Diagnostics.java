package com.wxmyyds.discordautotranslate.core;

/** Writes privacy-safe layout diagnostics to the framework and Android logs. */
public final class Diagnostics {

    private Diagnostics() {
    }

    public static void record(String line) {
        if (line != null && !line.isEmpty()) {
            XLog.i(line);
        }
    }
}
