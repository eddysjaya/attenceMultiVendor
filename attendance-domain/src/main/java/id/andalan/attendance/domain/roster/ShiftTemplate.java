package id.andalan.attendance.domain.roster;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pola shift milik satu situs. {@code endLocal <= startLocal} berarti shift berakhir
 * pada hari sipil berikutnya (shift malam). Hari kerja memakai {@link DayOfWeek}
 * (ISO-8601: Senin=1 ... Minggu=7), sama dengan kolom {@code days_of_week}.
 */
public record ShiftTemplate(
        UUID id,
        UUID siteId,
        String name,
        LocalTime startLocal,
        LocalTime endLocal,
        List<BreakWindow> breaks,
        Set<DayOfWeek> daysOfWeek,
        UUID payPolicyId) {

    public ShiftTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(startLocal, "startLocal");
        Objects.requireNonNull(endLocal, "endLocal");
        breaks = List.copyOf(breaks);
        if (daysOfWeek.isEmpty()) {
            throw new IllegalArgumentException("template " + name + " tanpa hari kerja");
        }
        daysOfWeek = Set.copyOf(EnumSet.copyOf(daysOfWeek));
    }

    /** True bila shift berakhir di hari sipil berikutnya. */
    public boolean crossesMidnight() {
        return !endLocal.isAfter(startLocal);
    }

    public boolean runsOn(DayOfWeek day) {
        return daysOfWeek.contains(day);
    }
}
