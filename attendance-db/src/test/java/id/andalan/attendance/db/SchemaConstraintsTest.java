package id.andalan.attendance.db;

import static id.andalan.attendance.db.Fixtures.COMPANY;
import static id.andalan.attendance.db.Fixtures.POLICY;
import static id.andalan.attendance.db.Fixtures.exec;
import static id.andalan.attendance.db.Fixtures.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Aturan yang dokumen desain wajibkan hidup di basis data, bukan hanya di aplikasi. */
class SchemaConstraintsTest {

    static final String UNIQUE = "23505";
    static final String CHECK = "23514";
    static final String FOREIGN_KEY = "23503";

    static TestDatabase db;
    static Connection c;
    static UUID site;
    static UUID otherSite;
    static UUID employee;

    @BeforeAll
    static void setUp() throws SQLException {
        db = TestDatabase.create();
        c = db.connection();
        site = Fixtures.site(c, "Jakarta Pusat", "Asia/Jakarta");
        otherSite = Fixtures.site(c, "Makassar", "Asia/Makassar");
        employee = Fixtures.employee(c, site, "E001");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (c != null) {
            c.close();
        }
        if (db != null) {
            db.close();
        }
    }

    // ---------------------------------------------------------------- seed

    @Test
    void seed_paypolicy_ada_dengan_penomoran_hari_iso8601_dan_nilai_rev4() throws SQLException {
        try (var ps = c.prepareStatement("""
                SELECT name, version, config->>'dow_numbering', config->'params'->>'min_rest_hours',
                       config->'presets'->'five_day'->'weekly_rest_dows'
                FROM pay_policies WHERE id = ?""")) {
            ps.setObject(1, POLICY);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("pp35-default-2026");
                assertThat(rs.getInt(2)).isEqualTo(1);
                assertThat(rs.getString(3)).isEqualTo("iso8601");
                assertThat(rs.getString(4)).isEqualTo("1");
                assertThat(rs.getString(5)).isEqualTo("[6, 7]");
            }
        }
    }

    @Test
    void parameter_kuota_di_luar_rentang_validasi_ditolak() {
        assertThatThrownBy(() -> exec(c, """
                INSERT INTO pay_policies (company_id, name, version, effective_from, config)
                SELECT company_id, name, 2, DATE '2026-10-01',
                       jsonb_set(config, '{params,quota_min_block_minutes}', '300')
                FROM pay_policies WHERE id = ?""", POLICY))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    // ---------------------------------------------------------------- situs & template

    @Test
    void zona_waktu_yang_tidak_dikenal_ditolak() {
        assertThatThrownBy(() -> Fixtures.site(c, "Salah", "Asia/Bandung"))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    @Test
    void template_default_harus_milik_situs_yang_sama() throws SQLException {
        UUID templateLain = Fixtures.template(c, otherSite, "Pagi MKS", "08:00", "17:00", "[]", "{1,2,3,4,5}");

        assertThatThrownBy(() -> Fixtures.setDefaultTemplate(c, site, templateLain))
                .hasFieldOrPropertyWithValue("SQLState", FOREIGN_KEY);
    }

    @Test
    void menghapus_template_default_hanya_mengosongkan_default_situs() throws SQLException {
        UUID s = Fixtures.site(c, "Surabaya", "Asia/Jakarta");
        UUID t = Fixtures.template(c, s, "Pagi SBY", "08:00", "17:00", "[]", "{1,2,3,4,5}");
        Fixtures.setDefaultTemplate(c, s, t);

        exec(c, "DELETE FROM shift_templates WHERE id = ?", t);

        try (var ps = c.prepareStatement("SELECT id, default_template_id FROM sites WHERE id = ?")) {
            ps.setObject(1, s);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(s);
                assertThat(rs.getObject(2)).isNull();
            }
        }
    }

    @Test
    void hari_kerja_di_luar_1_sampai_7_ditolak() {
        assertThatThrownBy(() -> Fixtures.template(c, site, "Minggu0", "08:00", "17:00", "[]", "{0,1}"))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    @Test
    void bentuk_assignment_campuran_ditolak() throws SQLException {
        UUID t = Fixtures.template(c, site, "Pagi A", "08:00", "17:00", "[]", "{1,2,3,4,5}");

        assertThatThrownBy(() -> exec(c, """
                INSERT INTO shift_assignments (employee_id, template_id, civil_date, start_date)
                VALUES (?, ?, DATE '2026-10-01', DATE '2026-10-01')""", employee, t))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    // ---------------------------------------------------------------- perangkat & identitas

    @Test
    void protokol_harus_cocok_dengan_vendor() {
        assertThatThrownBy(() -> Fixtures.device(c, site, "zkteco", "ZK-X", "hik_isup"))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    @Test
    void pin_bersama_tidak_boleh_ganda_walaupun_device_id_null() throws SQLException {
        UUID e2 = Fixtures.employee(c, site, "E002");
        exec(c, "INSERT INTO employee_device_identities (employee_id, vendor, pin) VALUES (?, 'zkteco', '1001')", employee);

        assertThatThrownBy(() -> exec(c,
                "INSERT INTO employee_device_identities (employee_id, vendor, pin) VALUES (?, 'zkteco', '1001')", e2))
                .hasFieldOrPropertyWithValue("SQLState", UNIQUE);
    }

    @Test
    void pin_per_perangkat_unik_dan_baris_removed_tidak_memblokir_pemakaian_ulang() throws SQLException {
        UUID dev = Fixtures.device(c, site, "hikvision", "HIK-1", "hik_isup");
        UUID e3 = Fixtures.employee(c, site, "E003");
        exec(c, "INSERT INTO employee_device_identities (employee_id, vendor, device_id, pin) VALUES (?, 'hikvision', ?, '77')",
                employee, dev);

        assertThatThrownBy(() -> exec(c,
                "INSERT INTO employee_device_identities (employee_id, vendor, device_id, pin) VALUES (?, 'hikvision', ?, '77')",
                e3, dev)).hasFieldOrPropertyWithValue("SQLState", UNIQUE);

        exec(c, "UPDATE employee_device_identities SET enroll_status = 'removed' WHERE device_id = ? AND pin = '77'", dev);
        assertThatCode(() -> exec(c,
                "INSERT INTO employee_device_identities (employee_id, vendor, device_id, pin) VALUES (?, 'hikvision', ?, '77')",
                e3, dev)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- kalender

    @Test
    void kalender_nasional_unik_per_tanggal_dan_baris_situs_boleh_menimpa() throws SQLException {
        LocalDate d = LocalDate.of(2026, 12, 25);
        exec(c, "INSERT INTO calendar_days (company_id, civil_date, day_type, name) VALUES (?, ?, 'public_holiday', 'Natal')",
                COMPANY, d);

        assertThatThrownBy(() -> exec(c,
                "INSERT INTO calendar_days (company_id, civil_date, day_type) VALUES (?, ?, 'weekday')", COMPANY, d))
                .hasFieldOrPropertyWithValue("SQLState", UNIQUE);
        assertThatCode(() -> exec(c,
                "INSERT INTO calendar_days (company_id, site_id, civil_date, day_type, name) VALUES (?, ?, ?, 'weekday', 'Hari kerja pengganti')",
                COMPANY, site, d)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- lembur kuota

    @Test
    void order_approved_tanpa_effective_from_ditolak_tetapi_cancel_dari_requested_boleh() throws SQLException {
        assertThatThrownBy(() -> exec(c, """
                INSERT INTO overtime_orders (employee_id, attendance_date, quota_minutes, reason, status,
                                             requested_by, approved_by, approved_at, pay_policy_id)
                VALUES (?, DATE '2026-09-28', 180, 'uji', 'approved', 'spv', 'spv', now(), ?)""", employee, POLICY))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);

        assertThatCode(() -> exec(c, """
                INSERT INTO overtime_orders (employee_id, attendance_date, quota_minutes, reason, status,
                                             requested_by, cancelled_by, cancelled_at, pay_policy_id)
                VALUES (?, DATE '2026-09-28', 180, 'uji', 'cancelled', 'spv', 'spv', now(), ?)""", employee, POLICY))
                .doesNotThrowAnyException();
    }

    @Test
    void effective_from_harus_sejajar_menit() {
        assertThatThrownBy(() -> exec(c, """
                INSERT INTO overtime_orders (employee_id, attendance_date, quota_minutes, reason, status, requested_by,
                                             approved_by, approved_at, effective_from, pay_policy_id)
                VALUES (?, DATE '2026-09-28', 60, 'uji', 'approved', 'spv', 'spv', now(),
                        TIMESTAMPTZ '2026-09-28 10:00:30+00', ?)""", employee, POLICY))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    // ---------------------------------------------------------------- sesi berversi

    @Test
    void satu_sesi_open_current_per_karyawan_per_run_mode() throws SQLException {
        UUID e = Fixtures.employee(c, site, "E010");
        UUID run = matchRun(e);
        UUID p1 = punch("k1");
        UUID p2 = punch("k2");

        exec(c, sessionSql("live", "open"), e, p1, run);
        assertThatThrownBy(() -> exec(c, sessionSql("live", "open"), e, p2, run))
                .hasFieldOrPropertyWithValue("SQLState", UNIQUE);
        // shadow punya rantai current sendiri
        assertThatCode(() -> exec(c, sessionSql("shadow", "open"), e, p2, run)).doesNotThrowAnyException();
    }

    @Test
    void sesi_bukan_current_wajib_punya_superseded_at() throws SQLException {
        UUID e = Fixtures.employee(c, site, "E011");
        UUID run = matchRun(e);
        UUID p = punch("k3");

        assertThatThrownBy(() -> exec(c, """
                INSERT INTO work_sessions (employee_id, attendance_date, run_mode, in_punch_id, opened_at, status,
                                           version, current, match_run_id, result_hash)
                VALUES (?, DATE '2026-09-28', 'live', ?, now(), 'open', 1, false, ?, 'h')""", e, p, run))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    @Test
    void status_superseded_tidak_ada_lagi() throws SQLException {
        UUID e = Fixtures.employee(c, site, "E012");
        UUID run = matchRun(e);
        UUID p = punch("k4");

        assertThatThrownBy(() -> exec(c, sessionSql("live", "superseded"), e, p, run))
                .hasFieldOrPropertyWithValue("SQLState", CHECK);
    }

    @Test
    void idempotency_key_punch_unik() throws SQLException {
        punch("dupe");
        assertThatThrownBy(() -> punch("dupe")).hasFieldOrPropertyWithValue("SQLState", UNIQUE);
    }

    // ---------------------------------------------------------------- util

    private static String sessionSql(String runMode, String status) {
        return """
                INSERT INTO work_sessions (employee_id, attendance_date, run_mode, in_punch_id, opened_at, status,
                                           version, current, match_run_id, result_hash)
                VALUES (?, DATE '2026-09-28', '%s', ?, now(), '%s', 1, true, ?, 'h')""".formatted(runMode, status);
    }

    private static UUID matchRun(UUID employee) throws SQLException {
        return insert(c, """
                INSERT INTO match_runs (employee_id, run_mode, from_attendance_date, to_attendance_date, reason, matcher_version)
                VALUES (?, 'live', DATE '2026-09-28', DATE '2026-09-29', 'punch', 'uji') RETURNING id""", employee);
    }

    private static UUID punch(String key) throws SQLException {
        return insert(c, """
                INSERT INTO punches (vendor, device_sn, employee_pin, occurred_at, device_local_time, verify_mode,
                                     door_direction, door_direction_source, idempotency_key)
                VALUES ('zkteco', 'SN1', '1001', now(), localtimestamp, 'face', 'in', 'device', ?) RETURNING id""", key);
    }
}
