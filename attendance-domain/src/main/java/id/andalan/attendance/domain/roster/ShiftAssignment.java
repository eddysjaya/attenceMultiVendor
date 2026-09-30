package id.andalan.attendance.domain.roster;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Penugasan template ke karyawan. Tepat satu bentuk:
 * <ul>
 *   <li>override harian: {@code civilDate} diisi, {@code startDate}/{@code endDate} null;</li>
 *   <li>rentang: {@code civilDate} null, {@code startDate} wajib, {@code endDate} null = terbuka.</li>
 * </ul>
 * Sama dengan CHECK {@code ck_shift_assignments_form} di basis data.
 */
public record ShiftAssignment(
        UUID id,
        UUID employeeId,
        UUID templateId,
        LocalDate civilDate,
        LocalDate startDate,
        LocalDate endDate) {

    public ShiftAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(templateId, "templateId");
        boolean daily = civilDate != null && startDate == null && endDate == null;
        boolean range = civilDate == null && startDate != null
                && (endDate == null || !endDate.isBefore(startDate));
        if (!daily && !range) {
            throw new IllegalArgumentException("bentuk assignment tidak valid: " + civilDate + "/" + startDate + "/" + endDate);
        }
    }

    public static ShiftAssignment daily(UUID id, UUID employeeId, UUID templateId, LocalDate date) {
        return new ShiftAssignment(id, employeeId, templateId, date, null, null);
    }

    public static ShiftAssignment range(UUID id, UUID employeeId, UUID templateId, LocalDate start, LocalDate endOrNull) {
        return new ShiftAssignment(id, employeeId, templateId, null, start, endOrNull);
    }

    public boolean isDailyOverride() {
        return civilDate != null;
    }

    /** Rentang yang mencakup {@code date}; override harian tidak pernah "mencakup" lewat jalur ini. */
    public boolean rangeCovers(LocalDate date) {
        return !isDailyOverride()
                && !date.isBefore(startDate)
                && (endDate == null || !date.isAfter(endDate));
    }
}
