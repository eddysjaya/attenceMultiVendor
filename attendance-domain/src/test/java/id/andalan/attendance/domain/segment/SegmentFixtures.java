package id.andalan.attendance.domain.segment;

import id.andalan.attendance.domain.roster.BreakWindow;
import id.andalan.attendance.domain.roster.RosterMaterializer;
import id.andalan.attendance.domain.roster.ShiftInstance;
import id.andalan.attendance.domain.roster.ShiftTemplate;
import id.andalan.attendance.domain.roster.SiteRoster;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Jadwal contoh dokumen desain (WIB) yang dibuat lewat {@link RosterMaterializer}, bukan jendela tulisan tangan. */
final class SegmentFixtures {

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    static final UUID SITE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID EMP = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-000000000101");

    /** Shift reguler contoh: 08:00–17:00, istirahat tak dibayar 12:00–13:00, Senin–Jumat. */
    static final ShiftTemplate PAGI = template("Pagi", "08:00", "17:00", true, EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    /** Sama, tetapi juga dijadwalkan Sabtu (untuk uji hari istirahat yang dijadwalkan). */
    static final ShiftTemplate PAGI_SENIN_SABTU = template("Pagi Sen-Sab", "08:00", "17:00", true,
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
    /** Shift yang berakhir 22:00 untuk varian reset braket kasus A. */
    static final ShiftTemplate SIANG = template("Siang", "13:00", "22:00", false, EnumSet.allOf(DayOfWeek.class));

    static final DateTimeFormatter WIB_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** Kuota pada contoh disetujui Jumat 2026-09-25 14:00 WIB. */
    static final Instant APPROVED_FRIDAY = wib("2026-09-25 14:00");

    static ShiftInstance instance(ShiftTemplate t, LocalDate date) {
        var site = new SiteRoster(SITE, WIB, t.id());
        var result = RosterMaterializer.materialize(site, List.of(EMP), date, date, Map.of(t.id(), t), Map.of());
        return result.instances().getFirst();
    }

    static ClosedSession session(ShiftTemplate t, LocalDate date, String in, String out, QuotaOrder... orders) {
        return new ClosedSession(date, WIB, wib(in), wib(out), List.of(instance(t, date)), List.of(orders));
    }

    static QuotaOrder order(int n, Instant effectiveFrom, int quota) {
        return new QuotaOrder(UUID.fromString(String.format("60000000-0000-0000-0000-%012d", n)), effectiveFrom, quota);
    }

    static Instant wib(String yyyyMmDdHhMm) {
        return LocalDateTime.parse(yyyyMmDdHhMm, WIB_MINUTE).atZone(WIB).toInstant();
    }

    /** Satu baris seperti tabel dokumen: start | end | menit | kind | civil_date | rate_code | within_cap. */
    static String row(Segment s) {
        String cap = s.withinCap() == null ? "n/a" : s.withinCap().toString();
        return String.join(" | ",
                SHORT.format(s.start().atZone(WIB)), SHORT.format(s.end().atZone(WIB)),
                Integer.toString(s.minutes()), s.kind().name().toLowerCase(),
                s.civilDate().format(DateTimeFormatter.ofPattern("MM-dd")), s.rateCode(), cap);
    }

    static List<String> rows(SegmentationResult r) {
        return r.segments().stream().map(SegmentFixtures::row).toList();
    }

    private static ShiftTemplate template(String name, String start, String end, boolean lunch, EnumSet<DayOfWeek> days) {
        List<BreakWindow> breaks = lunch
                ? List.of(new BreakWindow(LocalTime.of(12, 0), LocalTime.of(13, 0), false))
                : List.of();
        return new ShiftTemplate(UUID.nameUUIDFromBytes(name.getBytes()), SITE, name,
                LocalTime.parse(start), LocalTime.parse(end), breaks, days, POLICY);
    }

    private SegmentFixtures() {
    }
}
