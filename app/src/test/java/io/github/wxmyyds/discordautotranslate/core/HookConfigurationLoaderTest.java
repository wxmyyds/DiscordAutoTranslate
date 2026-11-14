package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONException;
import org.junit.Test;

public class HookConfigurationLoaderTest {
    @Test
    public void readsCurrentApkProfileWithoutReusingOldGenerationJson() throws Exception {
        Path apk = Files.createTempFile("hook-profile", ".zip");
        try {
            writeProfile(apk, "{\"schemaVersion\":1,\"versionCode\":352017,\"hooks\":{}} ");
            assertEquals(352017, HookConfigurationLoader.load(apk.toString(), 352017).versionCode());
            assertNull(HookConfigurationLoader.load(apk.toString(), 340013));
            writeProfile(apk, "{\"schemaVersion\":1,\"versionCode\":352017,\"hooks\":{},\"members\":{\"generation\":\"new-apk\"}}");
            assertEquals("new-apk", HookConfigurationLoader.load(apk.toString(), 352017)
                    .string("members", "generation"));
            writeProfile(apk, "{\"schemaVersion\":1,\"versionCode\":340013,\"hooks\":{}}");
            assertThrows(JSONException.class, () -> HookConfigurationLoader.load(apk.toString(), 352017));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    @Test
    public void missingOrMalformedApkIsAnErrorNotAnAlternateResourceLoad() throws Exception {
        assertThrows(IOException.class, () -> HookConfigurationLoader.load(null, 352017));
        assertThrows(IOException.class, () -> HookConfigurationLoader.load("", 352017));
        Path apk = Files.createTempFile("invalid-hook-profile", ".zip");
        try {
            Files.writeString(apk, "not an APK");
            assertThrows(IOException.class, () -> HookConfigurationLoader.load(apk.toString(), 352017));
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    private static void writeProfile(Path path, String profile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("assets/hook-config/352017.json"));
            zip.write(profile.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
