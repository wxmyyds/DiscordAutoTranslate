package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.BufferedReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads the exact hook profile matching the installed Discord version. */
public final class HookConfigurationLoader {
    private static final String CONFIG_DIRECTORY = "hook-config/";

    private HookConfigurationLoader() {
    }

    @Nullable
    public static HookConfiguration load(String moduleApkPath, int versionCode)
            throws IOException, JSONException {
        if (moduleApkPath == null || moduleApkPath.isEmpty()) {
            throw new IOException("Module APK unavailable for hook profile loading");
        }
        String path = "assets/" + CONFIG_DIRECTORY + versionCode + ".json";
        try (ZipFile apk = new ZipFile(moduleApkPath)) {
            ZipEntry asset = apk.getEntry(path);
            if (asset == null) {
                return null;
            }
            try (InputStream stream = apk.getInputStream(asset);
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
    }
}
