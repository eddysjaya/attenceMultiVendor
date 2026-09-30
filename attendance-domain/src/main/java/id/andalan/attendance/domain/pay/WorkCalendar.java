package id.andalan.attendance.domain.pay;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Resolusi {@code day_type(site, d)} dari dokumen desain, urutan ketat:
 * <ol>
 *   <li>baris khusus situs (boleh menimpa libur nasional, termasuk hari kerja pengganti);</li>
 *   <li>baris nasional company;</li>
 *   <li>hari ISO di {@code weekly_rest_dows} preset aktif → istirahat mingguan;</li>
 *   <li>selain itu hari kerja.</li>
 * </ol>
 */
public record WorkCalendar(
        Map<LocalDate, DayType> siteDays,
        Map<LocalDate, DayType> nationalDays,
        Set<DayOfWeek> weeklyRestDays) {

    public WorkCalendar {
        siteDays = Map.copyOf(siteDays);
        nationalDays = Map.copyOf(nationalDays);
        weeklyRestDays = Set.copyOf(weeklyRestDays);
    }

    public DayType dayType(LocalDate date) {
        DayType site = siteDays.get(date);
        if (site != null) {
            return site;
        }
        DayType national = nationalDays.get(date);
        if (national != null) {
            return national;
        }
        return weeklyRestDays.contains(date.getDayOfWeek()) ? DayType.WEEKLY_REST : DayType.WEEKDAY;
    }
}
