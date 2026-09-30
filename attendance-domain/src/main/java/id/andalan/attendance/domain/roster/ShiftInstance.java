package id.andalan.attendance.domain.roster;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Satu jadwal untuk satu karyawan pada satu {@code attendanceDate} (tanggal sipil mulai jadwal
 * di zona situs). {@code scheduledEnd} boleh jatuh pada hari sipil berikutnya.
 */
public record ShiftInstance(
        UUID employeeId,
        UUID templateId,
        UUID sourceAssignmentId,
        LocalDate attendanceDate,
        ZoneId zone,
        Instant scheduledStart,
        Instant scheduledEnd,
        List<ScheduledBreak> breaks) {

    public ShiftInstance {
        if (!scheduledEnd.isAfter(scheduledStart)) {
            throw new IllegalArgumentException("scheduledEnd harus setelah scheduledStart");
        }
        breaks = List.copyOf(breaks);
    }

    public record ScheduledBreak(Instant start, Instant end, boolean paid) {
    }
}
