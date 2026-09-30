package id.andalan.attendance.domain.segment;

import static id.andalan.attendance.domain.segment.SegmentFixtures.APPROVED_FRIDAY;
import static id.andalan.attendance.domain.segment.SegmentFixtures.PAGI;
import static id.andalan.attendance.domain.segment.SegmentFixtures.PAGI_SENIN_SABTU;
import static id.andalan.attendance.domain.segment.SegmentFixtures.WIB;
import static id.andalan.attendance.domain.segment.SegmentFixtures.order;
import static id.andalan.attendance.domain.segment.SegmentFixtures.rows;
import static id.andalan.attendance.domain.segment.SegmentFixtures.session;
import static id.andalan.attendance.domain.segment.SegmentFixtures.wib;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.andalan.attendance.domain.pay.DayType;
import id.andalan.attendance.domain.pay.WorkCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Aturan segmenter di luar tiga kasus utama: kuota, blok minimum, persetujuan, hari istirahat, cap pekan. */
class SegmenterRulesTest {

    static final LocalDate RABU = LocalDate.of(2026, 9, 30);
    static final LocalDate SABTU = LocalDate.of(2026, 10, 3);

    @Test
    void menit_sebelum_persetujuan_tidak_dibayar_dan_muncul_sebagai_ot_without_quota() {
        // Joko: kerja lembur sejak 17:00, kuota 120 baru disetujui 18:30, pulang 19:30
        var s = session(PAGI, RABU, "2026-09-30 07:58", "2026-09-30 19:30", order(1, wib("2026-09-30 18:30"), 120));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).endsWith(
                "09-30 17:00 | 09-30 18:30 | 90 | unpaid | 09-30 | UNPAID_BEFORE_APPROVAL | n/a",
                "09-30 18:30 | 09-30 19:30 | 60 | ot | 09-30 | OT_WD_1_5 | true");
        assertThat(r.quotaUsed()).isEqualTo(60);
        assertThat(r.issues()).containsExactly(new SegmentationResult.Issue("ot_without_quota", "info",
                Map.of("minutes", 90, "before_approval", 90, "no_quota", 0, "over_quota", 0)));
    }

    @Test
    void pulang_sepuluh_menit_setelah_shift_di_bawah_blok_minimum_tidak_memakan_kuota() {
        var s = session(PAGI, RABU, "2026-09-30 08:00", "2026-09-30 17:10", order(1, APPROVED_FRIDAY, 120));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r).getLast()).isEqualTo("09-30 17:00 | 09-30 17:10 | 10 | unpaid | 09-30 | UNPAID_BELOW_MIN_BLOCK | n/a");
        assertThat(r.quotaUsed()).isZero();
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void kerja_di_luar_shift_tanpa_order_sama_sekali() {
        var s = session(PAGI, RABU, "2026-09-30 08:00", "2026-09-30 19:00");

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r).getLast()).isEqualTo("09-30 17:00 | 09-30 19:00 | 120 | unpaid | 09-30 | UNPAID_NO_QUOTA | n/a");
        assertThat(r.otMinutes()).isZero();
        assertThat(r.issues()).extracting(SegmentationResult.Issue::code).containsExactly("ot_without_quota");
    }

    @Test
    void kuota_boleh_dipakai_sebelum_shift_dan_dipakai_kronologis() {
        var s = session(PAGI, RABU, "2026-09-30 06:00", "2026-09-30 18:00", order(1, APPROVED_FRIDAY, 180));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).startsWith(
                "09-30 06:00 | 09-30 07:00 | 60 | ot | 09-30 | OT_WD_1_5 | true",
                "09-30 07:00 | 09-30 08:00 | 60 | ot | 09-30 | OT_WD_2_0 | true");
        // 120 menit kuota terpakai pagi; sore hanya sisa 60, braket tetap lanjut (tidak kembali ke 1,5)
        assertThat(rows(r)).endsWith("09-30 17:00 | 09-30 18:00 | 60 | ot | 09-30 | OT_WD_2_0 | true");
        assertThat(r.quotaUsed()).isEqualTo(180);
    }

    @Test
    void menit_sebelum_batas_pra_shift_tidak_memakan_kuota() {
        // batas pra-shift 240 menit: kuota hanya boleh dipakai mulai 04:00
        var s = session(PAGI, RABU, "2026-09-30 03:30", "2026-09-30 17:00", order(1, APPROVED_FRIDAY, 300));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r).getFirst()).isEqualTo("09-30 03:30 | 09-30 04:00 | 30 | unpaid | 09-30 | UNPAID_EARLY | n/a");
        assertThat(r.otMinutes()).isEqualTo(240);
    }

    @Test
    void count_early_in_as_work_menjadikan_menit_pra_shift_tanpa_kuota_reguler() {
        var s = session(PAGI, RABU, "2026-09-30 07:55", "2026-09-30 17:00");

        var r = Segmenter.segment(s, Policies.pp35(Policies.params(true)), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r).getFirst()).isEqualTo("09-30 07:55 | 09-30 08:00 | 5 | regular | 09-30 | REG | n/a");
    }

    @Test
    void dua_order_pada_tanggal_sama_dipakai_fifo_menurut_effective_from() {
        var o1 = order(1, APPROVED_FRIDAY, 60);
        var o2 = order(2, wib("2026-09-30 17:30"), 60);
        var s = session(PAGI, RABU, "2026-09-30 08:00", "2026-09-30 19:00", o1, o2);

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(r.segments().stream().filter(x -> x.kind() == Segment.Kind.OT).toList())
                .extracting(Segment::overtimeOrderId, Segment::minutes, Segment::rateCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(o1.id(), 60, "OT_WD_1_5"),
                        org.assertj.core.groups.Tuple.tuple(o2.id(), 60, "OT_WD_2_0"));
    }

    @Test
    void kuota_dan_braket_berlanjut_dari_sesi_sebelumnya_pada_tanggal_yang_sama() {
        var s = session(PAGI, RABU, "2026-09-30 08:00", "2026-09-30 18:00", order(1, APPROVED_FRIDAY, 120));

        // shift terbelah: sesi pertama sudah memakai 100 menit kuota dan 100 menit lembur
        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 100, 100, 100);

        assertThat(rows(r)).endsWith(
                "09-30 17:00 | 09-30 17:20 | 20 | ot | 09-30 | OT_WD_2_0 | true",
                "09-30 17:20 | 09-30 18:00 | 40 | unpaid | 09-30 | UNPAID_OVER_QUOTA | n/a");
    }

    @Test
    void cap_pekan_melampaui_menjadi_over_cap_dengan_exception_pekan() {
        var s = session(PAGI, RABU, "2026-09-30 08:00", "2026-09-30 19:00", order(1, APPROVED_FRIDAY, 120));

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 1000);

        assertThat(rows(r)).endsWith(
                "09-30 17:00 | 09-30 18:00 | 60 | ot | 09-30 | OT_WD_1_5 | true",
                "09-30 18:00 | 09-30 18:20 | 20 | ot | 09-30 | OT_WD_2_0 | true",
                "09-30 18:20 | 09-30 19:00 | 40 | ot | 09-30 | OT_OVER_CAP | false");
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("weekly_ot_cap_exceeded", "warning", Map.of("minutes", 40)));
    }

    @Test
    void shift_terjadwal_pada_sabtu_iso6_adalah_premium_istirahat_tanpa_kuota() {
        assertThat(Policies.calendar().dayType(SABTU)).isEqualTo(DayType.WEEKLY_REST);
        var s = session(PAGI_SENIN_SABTU, SABTU, "2026-10-03 08:00", "2026-10-03 17:00");

        var r = Segmenter.segment(s, Policies.pp35(), Policies.calendar(), 0, 0, 0);

        assertThat(rows(r)).containsExactly(
                "10-03 08:00 | 10-03 12:00 | 240 | ot | 10-03 | OT_REST_2_0 | true",
                "10-03 12:00 | 10-03 13:00 | 60 | break | 10-03 | UNPAID_BREAK | n/a",
                "10-03 13:00 | 10-03 17:00 | 240 | ot | 10-03 | OT_OVER_CAP | false");
        assertThat(r.issues()).containsExactly(
                new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", 240)));
    }

    @Test
    void hari_kerja_pengganti_pada_sabtu_dibayar_reguler() {
        var calendar = new WorkCalendar(Map.of(SABTU, DayType.WEEKDAY), Map.of(), Policies.SAT_SUN);
        var s = session(PAGI_SENIN_SABTU, SABTU, "2026-10-03 08:00", "2026-10-03 17:00");

        var r = Segmenter.segment(s, Policies.pp35(), calendar, 0, 0, 0);

        assertThat(r.minutes(Segment.Kind.REGULAR)).isEqualTo(480);
        assertThat(r.otMinutes()).isZero();
    }

    @Test
    void sesi_harus_sejajar_menit_dan_tidak_kosong() {
        Instant t = wib("2026-09-30 08:00");
        assertThatThrownBy(() -> new ClosedSession(RABU, WIB, t.plusSeconds(30), t.plusSeconds(3600), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClosedSession(RABU, WIB, t, t, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
