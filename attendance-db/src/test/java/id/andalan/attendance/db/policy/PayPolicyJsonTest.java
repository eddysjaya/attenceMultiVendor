package id.andalan.attendance.db.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.andalan.attendance.domain.pay.DayType;
import id.andalan.attendance.domain.pay.OtBracket;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** Seed V2 harus terurai persis menjadi kebijakan yang dipakai uji segmenter. Tidak butuh basis data. */
class PayPolicyJsonTest {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    static String seedJson() throws IOException {
        try (InputStream in = PayPolicyJsonTest.class.getResourceAsStream("/db/migration/V2__seed_default_pay_policy.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("$json$") + "$json$".length();
            int end = sql.indexOf("$json$", start);
            return sql.substring(start, end);
        }
    }

    @Test
    void seed_v2_terurai_menjadi_preset_five_day_dan_parameter_rev4() throws IOException {
        var p = PayPolicyJson.parse(ID, "pp35-default-2026", 1, seedJson());

        assertThat(p.weeklyRestDays()).isEqualTo(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        assertThat(p.weekdayOt()).extracting(OtBracket::widthMinutes, OtBracket::rateCode)
                .containsExactly(Tuple.tuple(60, "OT_WD_1_5"), Tuple.tuple(null, "OT_WD_2_0"));
        assertThat(p.bracketsFor(DayType.PUBLIC_HOLIDAY)).extracting(OtBracket::widthMinutes, OtBracket::rateCode)
                .containsExactly(Tuple.tuple(480, "OT_HOL_2_0"), Tuple.tuple(60, "OT_HOL_3_0"), Tuple.tuple(null, "OT_HOL_4_0"));
        assertThat(p.params().minRestHours()).isEqualTo(1.0);
        assertThat(p.params().quotaMinBlockMinutes()).isEqualTo(30);
        assertThat(p.params().quotaPreShiftMaxMinutes()).isEqualTo(240);
        assertThat(p.params().dailyOtCapMinutes()).isEqualTo(240);
        assertThat(p.params().weeklyOtCapMinutes()).isEqualTo(1080);
        assertThat(p.params().retroactiveApproval()).isEqualTo("deny");
        assertThat(p.params().countEarlyInAsWork()).isFalse();
    }

    @Test
    void mode_yang_belum_didukung_mesin_ditolak_bukan_diabaikan() throws IOException {
        String json = seedJson().replace("\"full_day_premium\"", "\"schedule_plus_premium_ot\"");

        assertThatThrownBy(() -> PayPolicyJson.parse(ID, "x", 2, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belum didukung");
    }

    @Test
    void kunci_parameter_yang_hilang_ditolak() throws IOException {
        String json = seedJson().replace("\"daily_ot_cap_minutes\": 240,", "");

        assertThatThrownBy(() -> PayPolicyJson.parse(ID, "x", 2, json))
                .hasMessageContaining("daily_ot_cap_minutes");
    }
}
