package id.andalan.attendance.domain.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkCalendarAndPolicyTest {

    static final Set<DayOfWeek> SAT_SUN = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    static final LocalDate SELASA = LocalDate.of(2026, 9, 29);
    static final LocalDate SABTU = LocalDate.of(2026, 10, 3);

    @Test
    void urutan_resolusi_situs_lalu_nasional_lalu_istirahat_mingguan_lalu_hari_kerja() {
        var cal = new WorkCalendar(
                Map.of(SELASA, DayType.WEEKDAY),                          // situs menimpa libur nasional
                Map.of(SELASA, DayType.PUBLIC_HOLIDAY, SABTU.plusDays(2), DayType.PUBLIC_HOLIDAY),
                SAT_SUN);

        assertThat(cal.dayType(SELASA)).isEqualTo(DayType.WEEKDAY);
        assertThat(cal.dayType(SABTU)).isEqualTo(DayType.WEEKLY_REST);          // ISO Sabtu = 6
        assertThat(cal.dayType(SABTU.plusDays(1))).isEqualTo(DayType.WEEKLY_REST);
        assertThat(cal.dayType(SABTU.plusDays(2))).isEqualTo(DayType.PUBLIC_HOLIDAY); // Senin libur nasional
        assertThat(cal.dayType(SELASA.plusDays(1))).isEqualTo(DayType.WEEKDAY);
    }

    @Test
    void braket_libur_nasional_menyalin_istirahat_mingguan_dengan_kode_ot_hol() {
        var policy = policy(List.of(
                new OtBracket(480, new BigDecimal("2.0"), "OT_REST_2_0"),
                new OtBracket(60, new BigDecimal("3.0"), "OT_REST_3_0"),
                new OtBracket(null, new BigDecimal("4.0"), "OT_REST_4_0")));

        assertThat(policy.bracketsFor(DayType.PUBLIC_HOLIDAY))
                .extracting(OtBracket::rateCode, OtBracket::widthMinutes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("OT_HOL_2_0", 480),
                        org.assertj.core.groups.Tuple.tuple("OT_HOL_3_0", 60),
                        org.assertj.core.groups.Tuple.tuple("OT_HOL_4_0", null));
    }

    @Test
    void braket_wajib_diakhiri_braket_tanpa_batas() {
        assertThatThrownBy(() -> policy(List.of(new OtBracket(480, new BigDecimal("2.0"), "OT_REST_2_0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PayPolicy policy(List<OtBracket> rest) {
        var params = new PayPolicy.Params(15, 30, 180, 0, 30, 120, 60, 1, 30, false,
                240, 30, 960, "deny", 15, true, 20, 240, 1080, 30);
        return new PayPolicy(UUID.randomUUID(), "uji", 1, SAT_SUN,
                List.of(new OtBracket(60, new BigDecimal("1.5"), "OT_WD_1_5"),
                        new OtBracket(null, new BigDecimal("2.0"), "OT_WD_2_0")),
                rest, "OT_HOL", "REG", "OT_OVER_CAP", params);
    }
}
