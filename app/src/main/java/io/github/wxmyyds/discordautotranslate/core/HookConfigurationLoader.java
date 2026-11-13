package io.github.wxmyyds.discordautotranslate.core;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads the exact hook profile matching the installed Discord version. */
public final class HookConfigurationLoader {
    private static final String CONFIG_DIRECTORY = "hook-config/";

    private HookConfigurationLoader() {
    }

    @Nullable
    public static HookConfiguration load(Context context, int versionCode)
            throws IOException, JSONException {
        String path = CONFIG_DIRECTORY + versionCode + ".json";
        InputStream input;
        try {
            input = ModuleResources.openAsset(context, path);
        } catch (FileNotFoundException ignored) {
            return null;
        }
        if (input == null) {
            return null;
        }
        try (InputStream stream = input;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                json.append(buffer, 0, read);
            }
            return HookConfiguration.parse(json.toString(), versionCode);
        }
    }

    public static HookConfiguration parse(String json, int versionCode) throws JSONException {
        return HookConfiguration.parse(json, versionCode);
    }
}
