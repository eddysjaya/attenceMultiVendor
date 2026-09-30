package id.andalan.attendance.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@code exception_key} deterministik (lihat bagian Rematch di dokumen desain): hash SHA-256
 * dari komponen identitas exception, dipisah {@code |}. Rematch meng-upsert berdasarkan kunci ini
 * sehingga exception tidak berganda dan status yang diputuskan HR tidak hilang.
 */
public final class ExceptionKeys {

    private ExceptionKeys() {
    }

    public static String of(Object... parts) {
        var sb = new StringBuilder();
        for (Object p : parts) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append(p == null ? "" : p.toString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e);
        }
    }
}
