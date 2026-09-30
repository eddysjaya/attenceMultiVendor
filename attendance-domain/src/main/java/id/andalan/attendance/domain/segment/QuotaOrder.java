package id.andalan.attendance.domain.segment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Order lembur kuota yang sudah {@code approved}: menit kuotanya boleh dipakai sejak {@code effectiveFrom}. */
public record QuotaOrder(UUID id, Instant effectiveFrom, int quotaMinutes) {

    public QuotaOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (quotaMinutes <= 0) {
            throw new IllegalArgumentException("kuota harus positif: " + quotaMinutes);
        }
    }
}
