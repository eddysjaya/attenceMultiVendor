package id.andalan.attendance.domain.segment;

import id.andalan.attendance.domain.roster.ShiftInstance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Masukan segmenter: satu sesi {@code closed} beserta rencananya.
 *
 * @param openedAt  IN, sudah dibulatkan ke bawah ke menit
 * @param closedAt  OUT, sudah dibulatkan ke bawah ke menit
 * @param shifts    shift instance rencana sesi ini (0, 1, atau 2 bila shift sehari digabung)
 * @param orders    order kuota approved pada {@code attendanceDate}
 */
public record ClosedSession(
        LocalDate attendanceDate,
        ZoneId zone,
        Instant openedAt,
        Instant closedAt,
        List<ShiftInstance> shifts,
        List<QuotaOrder> orders) {

    public ClosedSession {
        Objects.requireNonNull(attendanceDate, "attendanceDate");
        Objects.requireNonNull(zone, "zone");
        requireMinute(openedAt, "openedAt");
        requireMinute(closedAt, "closedAt");
        if (!closedAt.isAfter(openedAt)) {
            throw new IllegalArgumentException("closedAt harus setelah openedAt");
        }
        shifts = shifts.stream().sorted(Comparator.comparing(ShiftInstance::scheduledStart)).toList();
        orders = orders.stream()
                .sorted(Comparator.comparing(QuotaOrder::effectiveFrom).thenComparing(QuotaOrder::id))
                .toList();
    }

    /** Mulai shift pertama; null bila rencana tanpa shift. Menit sebelum ini adalah sisi pra-shift. */
    public Instant anchor() {
        return shifts.isEmpty() ? null : shifts.getFirst().scheduledStart();
    }

    private static void requireMinute(Instant t, String name) {
        Objects.requireNonNull(t, name);
        if (t.getEpochSecond() % 60 != 0 || t.getNano() != 0) {
            throw new IllegalArgumentException(name + " harus sejajar menit: " + t);
        }
    }
}
