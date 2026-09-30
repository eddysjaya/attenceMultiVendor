package id.andalan.attendance.domain.segment;

import static id.andalan.attendance.domain.segment.SegmentFixtures.APPROVED_FRIDAY;
import static id.andalan.attendance.domain.segment.SegmentFixtures.PAGI;
import static id.andalan.attendance.domain.segment.SegmentFixtures.SIANG;
import static id.andalan.attendance.domain.segment.SegmentFixtures.order;
import static id.andalan.attendance.domain.segment.SegmentFixtures.rows;
import static id.andalan.attendance.domain.segment.SegmentFixtures.session;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Kasus A, B, dan C dokumen desain, angka per baris. Tabel yang diharapkan disalin dari dokumen;
 * bila salah satu berubah, dokumen atau mesinnya yang salah — jangan sesuaikan angka uji diam-diam.
 */
class SegmenterCasesTest {

    static final LocalDate SENIN = LocalDate.of(2026, 9, 28);
    static final LocalDate SELASA = LocalDate.of(2026, 9, 29);

    @Test
    void kasus_A_lembur_kontinu_melewati_tengah_malam() {
        var s = session(PAGI, SENIN, "2026-09-28 07:55", "2026-09-29 01:50", order(1, APPROVED_FRIDAY, 540));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).containsExactly(
                "09-28 07:55 | 09-28 08:00 | 5 | unpaid | 09-28 | UNPAID_EARLY | n/a",
                "09-28 08:00 | 09-28 12:00 | 240 | regular | 09-28 | REG | n/a",
                "09-28 12:00 | 09-28 13:00 | 60 | break | 09-28 | UNPAID_BREAK | n/a",
                "09-28 13:00 | 09-28 17:00 | 240 | regular | 09-28 | REG | n/a",
                "09-28 17:00 | 09-28 18:00 | 60 | ot | 09-28 | OT_WD_1_5 | true",
                "09-28 18:00 | 09-28 21:00 | 180 | ot | 09-28 | OT_WD_2_0 | true",
                "09-28 21:00 | 09-29 00:00 | 180 | ot | 09-28 | OT_OVER_CAP | false",
                "09-29 00:00 | 09-29 01:50 | 110 | ot | 09-29 | OT_OVER_CAP | false");
        assertThat(r.segments()).allSatisfy(seg -> assertThat(seg.attendanceDate()).isEqualTo(SENIN));
        assertThat(r.segments().stream().mapToInt(Segment::minutes).sum()).isEqualTo(1075);
        assertThat(r.otMinutes()).isEqualTo(530);
        assertThat(r.quotaUsed()).isEqualTo(530);   // sisa 10 menit hangus
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", 290)));
    }

    @Test
    void kasus_A_bila_selasa_libur_menit_setelah_cap_tetap_over_cap() {
        var s = session(PAGI, SENIN, "2026-09-28 07:55", "2026-09-29 01:50", order(1, APPROVED_FRIDAY, 540));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendarWithHoliday(SELASA), 0, 0, 0);

        assertThat(rows(r).getLast()).isEqualTo("09-29 00:00 | 09-29 01:50 | 110 | ot | 09-29 | OT_OVER_CAP | false");
    }

    @Test
    void kasus_A_varian_reset_braket_hanya_bila_jenis_hari_berubah() {
        var s = session(SIANG, SENIN, "2026-09-28 13:00", "2026-09-29 01:00", order(1, APPROVED_FRIDAY, 180));

        var weekday = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);
        var holiday = Segmenter.segment(s, Policies.pp35(), Policies.calendarWithHoliday(SELASA), 0, 0, 0);

        assertThat(rows(weekday)).endsWith(
                "09-28 22:00 | 09-28 23:00 | 60 | ot | 09-28 | OT_WD_1_5 | true",
                "09-28 23:00 | 09-29 00:00 | 60 | ot | 09-28 | OT_WD_2_0 | true",
                "09-29 00:00 | 09-29 01:00 | 60 | ot | 09-29 | OT_WD_2_0 | true");   // braket lanjut
        assertThat(rows(holiday)).endsWith(
                "09-28 22:00 | 09-28 23:00 | 60 | ot | 09-28 | OT_WD_1_5 | true",
                "09-28 23:00 | 09-29 00:00 | 60 | ot | 09-28 | OT_WD_2_0 | true",
                "09-29 00:00 | 09-29 01:00 | 60 | ot | 09-29 | OT_HOL_2_0 | true");  // braket di-reset, skala libur
    }

    @Test
    void kasus_B_hari_pertama_senin() {
        var s = session(PAGI, SENIN, "2026-09-28 08:02", "2026-09-28 21:40", order(1, APPROVED_FRIDAY, 300));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).containsExactly(
                "09-28 08:02 | 09-28 12:00 | 238 | regular | 09-28 | REG | n/a",
                "09-28 12:00 | 09-28 13:00 | 60 | break | 09-28 | UNPAID_BREAK | n/a",
                "09-28 13:00 | 09-28 17:00 | 240 | regular | 09-28 | REG | n/a",
                "09-28 17:00 | 09-28 18:00 | 60 | ot | 09-28 | OT_WD_1_5 | true",
                "09-28 18:00 | 09-28 21:00 | 180 | ot | 09-28 | OT_WD_2_0 | true",
                "09-28 21:00 | 09-28 21:40 | 40 | ot | 09-28 | OT_OVER_CAP | false");
        assertThat(r.otMinutes()).isEqualTo(280);
        assertThat(r.minutes(Segment.Kind.REGULAR)).isEqualTo(478);
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", 40)));
    }

    @Test
    void kasus_B_hari_kedua_selasa_dengan_penghitung_pekan_dari_senin() {
        var s = session(PAGI, SELASA, "2026-09-29 07:58", "2026-09-29 22:05", order(2, APPROVED_FRIDAY, 300));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 280);

        assertThat(rows(r)).containsExactly(
                "09-29 07:58 | 09-29 08:00 | 2 | unpaid | 09-29 | UNPAID_EARLY | n/a",
                "09-29 08:00 | 09-29 12:00 | 240 | regular | 09-29 | REG | n/a",
                "09-29 12:00 | 09-29 13:00 | 60 | break | 09-29 | UNPAID_BREAK | n/a",
                "09-29 13:00 | 09-29 17:00 | 240 | regular | 09-29 | REG | n/a",
                "09-29 17:00 | 09-29 18:00 | 60 | ot | 09-29 | OT_WD_1_5 | true",
                "09-29 18:00 | 09-29 21:00 | 180 | ot | 09-29 | OT_WD_2_0 | true",
                "09-29 21:00 | 09-29 22:00 | 60 | ot | 09-29 | OT_OVER_CAP | false",
                "09-29 22:00 | 09-29 22:05 | 5 | unpaid | 09-29 | UNPAID_OVER_QUOTA | n/a");
        assertThat(r.otMinutes()).isEqualTo(300);   // bukan 305
        // 280 + 300 = 580 ≤ 1.080: tidak ada exception pekan; 5 menit di atas kuota < 15: tidak ada ot_without_quota
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", 60)));
    }

    @Test
    void kasus_C_kuota_besar_sampai_pagi_hari_berikutnya() {
        var s = session(PAGI, SENIN, "2026-09-28 08:00", "2026-09-29 06:40", order(1, APPROVED_FRIDAY, 960));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).containsExactly(
                "09-28 08:00 | 09-28 12:00 | 240 | regular | 09-28 | REG | n/a",
                "09-28 12:00 | 09-28 13:00 | 60 | break | 09-28 | UNPAID_BREAK | n/a",
                "09-28 13:00 | 09-28 17:00 | 240 | regular | 09-28 | REG | n/a",
                "09-28 17:00 | 09-28 18:00 | 60 | ot | 09-28 | OT_WD_1_5 | true",
                "09-28 18:00 | 09-28 21:00 | 180 | ot | 09-28 | OT_WD_2_0 | true",
                "09-28 21:00 | 09-29 00:00 | 180 | ot | 09-28 | OT_OVER_CAP | false",
                "09-29 00:00 | 09-29 06:40 | 400 | ot | 09-29 | OT_OVER_CAP | false");
        assertThat(r.segments().stream().mapToInt(Segment::minutes).sum()).isEqualTo(1360);
        assertThat(r.otMinutes()).isEqualTo(820);
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", 580)));
    }
}
