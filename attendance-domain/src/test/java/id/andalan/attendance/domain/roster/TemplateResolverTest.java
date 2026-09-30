package id.andalan.attendance.domain.roster;

import static id.andalan.attendance.domain.roster.RosterFixtures.EMP;
import static id.andalan.attendance.domain.roster.RosterFixtures.JAKARTA;
import static id.andalan.attendance.domain.roster.RosterFixtures.MALAM;
import static id.andalan.attendance.domain.roster.RosterFixtures.PAGI;
import static id.andalan.attendance.domain.roster.RosterFixtures.PAGI_SITUS_LAIN;
import static id.andalan.attendance.domain.roster.RosterFixtures.SABTU;
import static id.andalan.attendance.domain.roster.RosterFixtures.SITE;
import static id.andalan.attendance.domain.roster.RosterFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TemplateResolverTest {

    static final LocalDate SENIN = LocalDate.of(2026, 9, 28);
    static final LocalDate SABTU_TGL = LocalDate.of(2026, 10, 3);

    static final Map<UUID, ShiftTemplate> TEMPLATES = Stream.of(PAGI, MALAM, SABTU, PAGI_SITUS_LAIN)
            .collect(Collectors.toMap(ShiftTemplate::id, Function.identity()));

    static final SiteRoster TANPA_DEFAULT = new SiteRoster(SITE, JAKARTA, null);
    static final SiteRoster DEFAULT_PAGI = new SiteRoster(SITE, JAKARTA, PAGI.id());

    @Test
    void override_harian_menang_atas_rentang_dan_mengabaikan_hari_kerja_template() {
        var range = ShiftAssignment.range(id(1), EMP, PAGI.id(), SENIN.minusDays(7), null);
        var override = ShiftAssignment.daily(id(2), EMP, SABTU.id(), SENIN); // SABTU hanya Sabtu, tapi override eksplisit

        var r = TemplateResolver.resolve(SENIN, List.of(range, override), TEMPLATES, DEFAULT_PAGI);

        assertThat(r).isEqualTo(new TemplateResolution.Resolved(SABTU, id(2)));
    }

    @Test
    void rentang_dengan_start_date_terbaru_menang() {
        var lama = ShiftAssignment.range(id(1), EMP, PAGI.id(), SENIN.minusDays(30), null);
        var baru = ShiftAssignment.range(id(2), EMP, MALAM.id(), SENIN.minusDays(3), SENIN.plusDays(10));

        var r = TemplateResolver.resolve(SENIN, List.of(lama, baru), TEMPLATES, TANPA_DEFAULT);

        assertThat(r).isEqualTo(new TemplateResolution.Resolved(MALAM, id(2)));
    }

    @Test
    void dua_rentang_seri_adalah_ambigu_dan_tidak_menebak() {
        var a = ShiftAssignment.range(id(7), EMP, PAGI.id(), SENIN.minusDays(3), null);
        var b = ShiftAssignment.range(id(5), EMP, MALAM.id(), SENIN.minusDays(3), null);

        var r = TemplateResolver.resolve(SENIN, List.of(a, b), TEMPLATES, DEFAULT_PAGI);

        assertThat(r).isEqualTo(new TemplateResolution.Ambiguous(List.of(id(5), id(7))));
    }

    @Test
    void rentang_di_luar_hari_kerja_template_berarti_libur_dan_tidak_jatuh_ke_default() {
        var range = ShiftAssignment.range(id(1), EMP, PAGI.id(), SENIN.minusDays(7), null);
        var siteDenganDefaultSetiapHari = new SiteRoster(SITE, JAKARTA, MALAM.id());

        var r = TemplateResolver.resolve(SABTU_TGL, List.of(range), TEMPLATES, siteDenganDefaultSetiapHari);

        assertThat(r).isInstanceOf(TemplateResolution.NoShift.class);
    }

    @Test
    void rentang_yang_sudah_berakhir_tidak_berlaku() {
        var selesai = ShiftAssignment.range(id(1), EMP, MALAM.id(), SENIN.minusDays(10), SENIN.minusDays(1));

        var r = TemplateResolver.resolve(SENIN, List.of(selesai), TEMPLATES, DEFAULT_PAGI);

        assertThat(r).isEqualTo(new TemplateResolution.Resolved(PAGI, null));
    }

    @Test
    void default_situs_hanya_pada_hari_kerjanya_iso8601_sabtu_bukan_hari_kerja_pagi() {
        assertThat(SABTU_TGL.getDayOfWeek().getValue()).isEqualTo(6); // ISO-8601: Sabtu = 6

        assertThat(TemplateResolver.resolve(SENIN, List.of(), TEMPLATES, DEFAULT_PAGI))
                .isEqualTo(new TemplateResolution.Resolved(PAGI, null));
        assertThat(TemplateResolver.resolve(SABTU_TGL, List.of(), TEMPLATES, DEFAULT_PAGI))
                .isInstanceOf(TemplateResolution.NoShift.class);
    }

    @Test
    void default_milik_situs_lain_diabaikan() {
        var salahSitus = new SiteRoster(SITE, JAKARTA, PAGI_SITUS_LAIN.id());

        assertThat(TemplateResolver.resolve(SENIN, List.of(), TEMPLATES, salahSitus))
                .isInstanceOf(TemplateResolution.NoShift.class);
    }

    @Test
    void tanpa_assignment_dan_tanpa_default_tidak_ada_shift() {
        assertThat(TemplateResolver.resolve(SENIN, List.of(), TEMPLATES, TANPA_DEFAULT))
                .isInstanceOf(TemplateResolution.NoShift.class);
    }

    @Test
    void assignment_ke_template_yang_tidak_dimuat_adalah_kesalahan_data() {
        var hilang = ShiftAssignment.daily(id(1), EMP, UUID.randomUUID(), SENIN);

        assertThatThrownBy(() -> TemplateResolver.resolve(SENIN, List.of(hilang), TEMPLATES, TANPA_DEFAULT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bentuk_assignment_campuran_ditolak() {
        assertThatThrownBy(() -> new ShiftAssignment(id(1), EMP, PAGI.id(), SENIN, SENIN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftAssignment.range(id(1), EMP, PAGI.id(), SENIN, SENIN.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
