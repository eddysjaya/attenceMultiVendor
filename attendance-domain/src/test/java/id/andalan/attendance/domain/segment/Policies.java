package id.andalan.attendance.domain.segment;

import id.andalan.attendance.domain.pay.OtBracket;
import id.andalan.attendance.domain.pay.PayPolicy;
import id.andalan.attendance.domain.pay.WorkCalendar;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Kebijakan dan kalender uji: nilai sama dengan seed V2 {@code pp35-default-2026} (preset five_day). */
final class Policies {

    static final Set<DayOfWeek> SAT_SUN = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    static PayPolicy.Params params(boolean countEarlyInAsWork) {
        return new PayPolicy.Params(15, 30, 180, 0, 30, 120, 60, 1, 30, countEarlyInAsWork,
                240, 30, 960, "deny", 15, true, 20, 240, 1080, 30);
    }

    static PayPolicy pp35() {
        return pp35(params(false));
    }

    static PayPolicy pp35(PayPolicy.Params params) {
        return new PayPolicy(
                UUID.fromString("00000000-0000-0000-0000-000000000101"), "pp35-default-2026", 1, SAT_SUN,
                List.of(new OtBracket(60, new BigDecimal("1.5"), "OT_WD_1_5"),
                        new OtBracket(null, new BigDecimal("2.0"), "OT_WD_2_0")),
                List.of(new OtBracket(480, new BigDecimal("2.0"), "OT_REST_2_0"),
                        new OtBracket(60, new BigDecimal("3.0"), "OT_REST_3_0"),
                        new OtBracket(null, new BigDecimal("4.0"), "OT_REST_4_0")),
                "OT_HOL", "REG", "OT_OVER_CAP", params);
    }

    static WorkCalendar calendar() {
        return new WorkCalendar(Map.of(), Map.of(), SAT_SUN);
    }

    static WorkCalendar calendarWithHoliday(LocalDate holiday) {
        return new WorkCalendar(Map.of(), Map.of(holiday, id.andalan.attendance.domain.pay.DayType.PUBLIC_HOLIDAY), SAT_SUN);
    }

    private Policies() {
    }
}
