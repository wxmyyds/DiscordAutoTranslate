package com.wxmyyds.discordautotranslate.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SHA-256 摘要的十六进制编码。
 *
 * <p>原先 {@link Scheduler#contentHash} 与 {@link Config.Snapshot} 的凭据指纹
 * 各自维护一份相同的“摘要转十六进制”实现，此处合并为一份。</p>
 */
final class Sha256 {

    private Sha256() {
    }

    /**
     * 计算 {@code SHA-256(part1 + '\0' + part2)} 的小写十六进制串。
     *
     * <p>两段之间以空字节分隔，保证拼接无歧义（段内容不会跨界组合出相同摘要）。</p>
     *
     * @return 十六进制摘要；算法不可用（理论不可达）时返回 null，由调用方自行降级
     */
    static String digestHex(String part1, String part2) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(part1.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(part2.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
