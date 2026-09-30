package id.andalan.attendance.domain.roster;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Template dan situs contoh dari dokumen desain (Asia/Jakarta, shift pagi 08:00–17:00). */
final class RosterFixtures {

    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    static final UUID SITE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID OTHER_SITE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID POLICY = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID EMP = UUID.fromString("30000000-0000-0000-0000-000000000001");

    static final ShiftTemplate PAGI = new ShiftTemplate(
            UUID.fromString("40000000-0000-0000-0000-000000000001"), SITE, "Pagi",
            LocalTime.of(8, 0), LocalTime.of(17, 0),
            List.of(new BreakWindow(LocalTime.of(12, 0), LocalTime.of(13, 0), false)),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), POLICY);

    static final ShiftTemplate MALAM = new ShiftTemplate(
            UUID.fromString("40000000-0000-0000-0000-000000000002"), SITE, "Malam",
            LocalTime.of(22, 0), LocalTime.of(6, 0),
            List.of(new BreakWindow(LocalTime.of(2, 0), LocalTime.of(2, 30), false)),
            EnumSet.allOf(DayOfWeek.class), POLICY);

    static final ShiftTemplate SABTU = new ShiftTemplate(
            UUID.fromString("40000000-0000-0000-0000-000000000003"), SITE, "Sabtu setengah hari",
            LocalTime.of(8, 0), LocalTime.of(12, 0), List.of(),
            EnumSet.of(DayOfWeek.SATURDAY), POLICY);

    static final ShiftTemplate PAGI_SITUS_LAIN = new ShiftTemplate(
            UUID.fromString("40000000-0000-0000-0000-000000000009"), OTHER_SITE, "Pagi situs lain",
            LocalTime.of(8, 0), LocalTime.of(17, 0), List.of(),
            EnumSet.allOf(DayOfWeek.class), POLICY);

    static UUID id(int n) {
        return UUID.fromString(String.format("50000000-0000-0000-0000-%012d", n));
    }

    private RosterFixtures() {
    }
}
