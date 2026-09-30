package id.andalan.attendance.domain.roster;

import static id.andalan.attendance.domain.roster.RosterFixtures.EMP;
import static id.andalan.attendance.domain.roster.RosterFixtures.JAKARTA;
import static id.andalan.attendance.domain.roster.RosterFixtures.MALAM;
import static id.andalan.attendance.domain.roster.RosterFixtures.PAGI;
import static id.andalan.attendance.domain.roster.RosterFixtures.POLICY;
import static id.andalan.attendance.domain.roster.RosterFixtures.SITE;
import static id.andalan.attendance.domain.roster.RosterFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterMaterializerTest {

    static final LocalDate SENIN = LocalDate.of(2026, 9, 28);
    static final SiteRoster SITE_DEFAULT_PAGI = new SiteRoster(SITE, JAKARTA, PAGI.id());
    static final Map<UUID, ShiftTemplate> TEMPLATES = Map.of(PAGI.id(), PAGI, MALAM.id(), MALAM);

    @Test
    void shift_pagi_satu_pekan_hanya_senin_sampai_jumat_dengan_attendance_date_dan_instant_benar() {
        var result = RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP),
                SENIN, SENIN.plusDays(6), TEMPLATES, Map.of());

        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.instances()).extracting(ShiftInstance::attendanceDate)
                .containsExactly(SENIN, SENIN.plusDays(1), SENIN.plusDays(2), SENIN.plusDays(3), SENIN.plusDays(4));

        ShiftInstance senin = result.instances().getFirst();
        // 08:00 WIB = 01:00Z, 17:00 WIB = 10:00Z; istirahat 12:00–13:00 WIB = 05:00–06:00Z
        assertThat(senin.scheduledStart()).isEqualTo(Instant.parse("2026-09-28T01:00:00Z"));
        assertThat(senin.scheduledEnd()).isEqualTo(Instant.parse("2026-09-28T10:00:00Z"));
        assertThat(senin.breaks()).containsExactly(new ShiftInstance.ScheduledBreak(
                Instant.parse("2026-09-28T05:00:00Z"), Instant.parse("2026-09-28T06:00:00Z"), false));
        assertThat(senin.sourceAssignmentId()).isNull();
        assertThat(senin.zone()).isEqualTo(JAKARTA);
    }

    @Test
    void shift_malam_berakhir_hari_sipil_berikutnya_dan_istirahat_dini_hari_ikut_hari_berikutnya() {
        var override = ShiftAssignment.daily(id(1), EMP, MALAM.id(), SENIN);

        var result = RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP),
                SENIN, SENIN, TEMPLATES, Map.of(EMP, List.of(override)));

        ShiftInstance malam = result.instances().getFirst();
        assertThat(malam.attendanceDate()).isEqualTo(SENIN);                               // tanggal mulai jadwal
        assertThat(malam.scheduledStart()).isEqualTo(Instant.parse("2026-09-28T15:00:00Z")); // Sen 22:00 WIB
        assertThat(malam.scheduledEnd()).isEqualTo(Instant.parse("2026-09-28T23:00:00Z"));   // Sel 06:00 WIB
        assertThat(malam.breaks()).containsExactly(new ShiftInstance.ScheduledBreak(
                Instant.parse("2026-09-28T19:00:00Z"), Instant.parse("2026-09-28T19:30:00Z"), false)); // Sel 02:00–02:30 WIB
        assertThat(malam.sourceAssignmentId()).isEqualTo(id(1));
    }

    @Test
    void tanggal_ambigu_tidak_menghasilkan_instance_tetapi_tercatat() {
        var a = ShiftAssignment.range(id(1), EMP, PAGI.id(), SENIN, null);
        var b = ShiftAssignment.range(id(2), EMP, MALAM.id(), SENIN, null);

        var result = RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP),
                SENIN, SENIN, TEMPLATES, Map.of(EMP, List.of(a, b)));

        assertThat(result.instances()).isEmpty();
        assertThat(result.ambiguities()).containsExactly(
                new RosterMaterializer.Ambiguity(EMP, SENIN, List.of(id(1), id(2))));
    }

    @Test
    void istirahat_di_luar_jam_shift_ditolak() {
        var rusak = new ShiftTemplate(UUID.randomUUID(), SITE, "Rusak", LocalTime.of(8, 0), LocalTime.of(12, 0),
                List.of(new BreakWindow(LocalTime.of(13, 0), LocalTime.of(14, 0), false)),
                EnumSet.allOf(DayOfWeek.class), POLICY);
        var site = new SiteRoster(SITE, JAKARTA, rusak.id());

        assertThatThrownBy(() -> RosterMaterializer.materialize(site, List.of(EMP), SENIN, SENIN,
                Map.of(rusak.id(), rusak), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("di luar shift");
    }

    @Test
    void rentang_terbalik_atau_terlalu_panjang_ditolak() {
        assertThatThrownBy(() -> RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP),
                SENIN, SENIN.minusDays(1), TEMPLATES, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP),
                SENIN, SENIN.plusDays(RosterMaterializer.MAX_DAYS + 1), TEMPLATES, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasil_deterministik_urut_karyawan_lalu_tanggal() {
        UUID empB = UUID.fromString("30000000-0000-0000-0000-000000000002");

        var r1 = RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(empB, EMP), SENIN, SENIN.plusDays(1), TEMPLATES, Map.of());
        var r2 = RosterMaterializer.materialize(SITE_DEFAULT_PAGI, List.of(EMP, empB), SENIN, SENIN.plusDays(1), TEMPLATES, Map.of());

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.instances()).extracting(ShiftInstance::employeeId).containsExactly(EMP, EMP, empB, empB);
    }
}
