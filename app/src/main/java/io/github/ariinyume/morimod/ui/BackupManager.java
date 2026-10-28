package io.github.ariinyume.morimod.ui;

import io.github.ariinyume.morimod.config.MoriConfig;

/**
 * 备份导出 / 导入（FR-0.4）。
 *
 * <p>导出为**单文件文本**，带 schema 版本与校验值；图片与字体按「引用路径」记录，
 * 不复制二进制（避免备份体积失控）。导入时校验版本与校验值，不匹配则**不覆盖**现有配置。
 *
 * <pre>
 * MORIMOD-BACKUP 1
 * sha256:ABCDEF0123456789
 * {json}
 * </pre>
 */
public final class BackupManager {

    private static final String MAGIC = "MORIMOD-BACKUP";
    private static final int FORMAT_VERSION = 1;

    private BackupManager() {
    }

    public static String export(MoriConfig cfg) {
        String json = cfg.toJson();
        return MAGIC + " " + FORMAT_VERSION + "\n"
                + "sha256:" + checksum(json) + "\n"
                + json + "\n";
    }

    /** 导入。返回 null 表示校验失败 / 格式不符（调用方保持现有配置不变）。 */
    public static MoriConfig importText(String text) {
        if (text == null) return null;
        String[] lines = text.replace("\r\n", "\n").split("\n", 3);
        if (lines.length < 3) return null;
        String head = lines[0].trim();
        if (!head.startsWith(MAGIC + " ")) return null;
        try {
            int ver = Integer.parseInt(head.substring(MAGIC.length() + 1).trim());
            if (ver != FORMAT_VERSION) return null;
        } catch (Throwable t) {
            return null;
        }
        String sumLine = lines[1].trim();
        if (!sumLine.startsWith("sha256:")) return null;
        String expect = sumLine.substring("sha256:".length()).trim();

        String body = lines[2];
        // 允许尾部换行，保持与导出时一致
        String json = body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
        if (!checksum(json).equalsIgnoreCase(expect)) return null;
        return MoriConfig.fromJson(json);
    }

    /** sha-256 前 16 位十六进制。 */
    public static String checksum(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "0000000000000000";
        }
    }
}
