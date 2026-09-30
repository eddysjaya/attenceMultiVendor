package id.andalan.attendance.db.roster;

import static id.andalan.attendance.db.Fixtures.exec;
import static org.assertj.core.api.Assertions.assertThat;

import id.andalan.attendance.db.Fixtures;
import id.andalan.attendance.db.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RosterMaterializationServiceTest {

    static final LocalDate SENIN = LocalDate.of(2026, 9, 28);
    static final LocalDate MINGGU = SENIN.plusDays(6);

    TestDatabase db;
    Connection c;
    RosterMaterializationService service;
    UUID site;
    UUID pagi;
    UUID malam;
    UUID emp;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.create();
        c = db.connection();
        service = new RosterMaterializationService(db.dataSource());
        site = Fixtures.site(c, "Jakarta Pusat", "Asia/Jakarta");
        pagi = Fixtures.template(c, site, "Pagi", "08:00", "17:00",
                "[{\"start\":\"12:00\",\"end\":\"13:00\",\"paid\":false}]", "{1,2,3,4,5}");
        malam = Fixtures.template(c, site, "Malam", "22:00", "06:00",
                "[{\"start\":\"02:00\",\"end\":\"02:30\",\"paid\":false}]", "{1,2,3,4,5,6,7}");
        Fixtures.setDefaultTemplate(c, site, pagi);
        emp = Fixtures.employee(c, site, "E001");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (c != null) {
            c.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void satu_pekan_dari_template_default_menghasilkan_lima_instance_dan_mengantrekan_rematch() throws SQLException {
        var report = service.materialize(site, SENIN, MINGGU, null);

        assertThat(report.inserted()).isEqualTo(5);
        assertThat(report.updated()).isZero();
        assertThat(report.deleted()).isZero();
        assertThat(report.rematchEnqueued()).isEqualTo(Map.of(emp, SENIN));
        assertThat(instances()).extracting(Row::attendanceDate)
                .containsExactly(SENIN, SENIN.plusDays(1), SENIN.plusDays(2), SENIN.plusDays(3), SENIN.plusDays(4));
        assertThat(instances().getFirst().start()).isEqualTo(OffsetDateTime.parse("2026-09-28T01:00:00Z"));
        assertThat(matchJob()).isEqualTo(new Job(1, SENIN));
    }

    @Test
    void menjalankan_ulang_tanpa_perubahan_adalah_no_op_dan_tidak_menaikkan_generation() throws SQLException {
        service.materialize(site, SENIN, MINGGU, null);

        var again = service.materialize(site, SENIN, MINGGU, null);

        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(again.deleted()).isZero();
        assertThat(again.unchanged()).isEqualTo(5);
        assertThat(again.rematchEnqueued()).isEmpty();
        assertThat(matchJob()).isEqualTo(new Job(1, SENIN));
    }

    @Test
    void override_shift_malam_mengganti_instance_rabu_dan_rematch_mulai_rabu() throws SQLException {
        service.materialize(site, SENIN, MINGGU, null);
        LocalDate rabu = SENIN.plusDays(2);
        exec(c, "UPDATE match_jobs SET rematch_from = NULL"); // anggap job sebelumnya sudah diproses
        Fixtures.dailyAssignment(c, emp, malam, rabu);

        var report = service.materialize(site, SENIN, MINGGU, null);

        assertThat(report.inserted()).isEqualTo(1);   // instance Malam Rabu
        assertThat(report.deleted()).isEqualTo(1);    // instance Pagi Rabu dihapus
        assertThat(report.rematchEnqueued()).isEqualTo(Map.of(emp, rabu));
        Row malamRabu = instances().stream().filter(r -> r.attendanceDate().equals(rabu)).findFirst().orElseThrow();
        assertThat(malamRabu.templateId()).isEqualTo(malam);
        // Rab 22:00 WIB = 15:00Z; Kam 06:00 WIB = 23:00Z (hari sipil berikutnya, attendance_date tetap Rabu)
        assertThat(malamRabu.start()).isEqualTo(OffsetDateTime.parse("2026-09-30T15:00:00Z"));
        assertThat(malamRabu.end()).isEqualTo(OffsetDateTime.parse("2026-09-30T23:00:00Z"));
        assertThat(matchJob()).isEqualTo(new Job(2, rabu));
    }

    @Test
    void rentang_seri_menjadi_roster_ambiguous_tanpa_instance_lalu_obsolete_setelah_diperbaiki() throws SQLException {
        UUID a1 = Fixtures.rangeAssignment(c, emp, pagi, SENIN, null);
        UUID a2 = Fixtures.rangeAssignment(c, emp, malam, SENIN, null);

        var report = service.materialize(site, SENIN, SENIN, null);

        assertThat(report.inserted()).isZero();
        assertThat(report.ambiguous()).isEqualTo(1);
        assertThat(exceptionStatuses()).containsExactly("open");

        // idempoten: tidak menggandakan exception
        service.materialize(site, SENIN, SENIN, null);
        assertThat(exceptionStatuses()).containsExactly("open");

        exec(c, "DELETE FROM shift_assignments WHERE id = ?", a2);
        var fixed = service.materialize(site, SENIN, SENIN, null);

        assertThat(fixed.inserted()).isEqualTo(1);
        assertThat(fixed.exceptionsObsoleted()).isEqualTo(1);
        assertThat(exceptionStatuses()).containsExactly("obsolete");
        assertThat(instances()).extracting(Row::templateId).containsExactly(pagi);
        assertThat(a1).isNotNull();
    }

    @Test
    void karyawan_keluar_tidak_dimaterialisasi_bila_daftar_karyawan_tidak_diberikan() throws SQLException {
        exec(c, "UPDATE employees SET status = 'exited', exited_at = now() WHERE id = ?", emp);

        var report = service.materialize(site, SENIN, MINGGU, null);

        assertThat(report.inserted()).isZero();
        assertThat(instances()).isEmpty();
    }

    // ---------------------------------------------------------------- util

    record Row(LocalDate attendanceDate, UUID templateId, OffsetDateTime start, OffsetDateTime end) {
    }

    record Job(long generation, LocalDate rematchFrom) {
    }

    private List<Row> instances() throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (var ps = c.prepareStatement("""
                SELECT attendance_date, template_id, scheduled_start, scheduled_end
                FROM shift_instances WHERE employee_id = ? ORDER BY attendance_date""")) {
            ps.setObject(1, emp);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getObject(1, LocalDate.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                            rs.getObject(4, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)));
                }
            }
        }
        return rows;
    }

    private Job matchJob() throws SQLException {
        try (var ps = c.prepareStatement("SELECT generation, rematch_from FROM match_jobs WHERE employee_id = ?")) {
            ps.setObject(1, emp);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? new Job(rs.getLong(1), rs.getObject(2, LocalDate.class)) : null;
            }
        }
    }

    private List<String> exceptionStatuses() throws SQLException {
        List<String> s = new ArrayList<>();
        try (var ps = c.prepareStatement(
                "SELECT status FROM attendance_exceptions WHERE employee_id = ? AND code = 'roster_ambiguous'")) {
            ps.setObject(1, emp);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.add(rs.getString(1));
                }
            }
        }
        return s;
    }
}
