package id.andalan.attendance.domain.segment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Irisan bayar setengah-terbuka {@code [start, end)}. {@code withinCap} hanya diisi untuk {@code kind = OT};
 * {@code overtimeOrderId} hanya untuk menit lembur yang memakai kuota order.
 */
public record Segment(
        Instant start,
        Instant end,
        int minutes,
        Kind kind,
        LocalDate attendanceDate,
        LocalDate civilDate,
        String rateCode,
        Boolean withinCap,
        UUID overtimeOrderId) {

    public enum Kind {
        REGULAR, OT, BREAK, UNPAID
    }

    public Segment {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("segmen kosong " + start + " .. " + end);
        }
        if ((kind == Kind.OT) != (withinCap != null)) {
            throw new IllegalArgumentException("withinCap hanya untuk segmen OT");
        }
    }
}
