package id.andalan.attendance.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/** Penyisip baris uji minimal lewat SQL biasa. */
public final class Fixtures {

    public static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private Fixtures() {
    }

    public static UUID site(Connection c, String name, String tz) throws SQLException {
        return insert(c, "INSERT INTO sites (company_id, name, timezone) VALUES (?, ?, ?) RETURNING id",
                COMPANY, name, tz);
    }

    /** @param days ISO-8601, mis. "{1,2,3,4,5}" */
    public static UUID template(Connection c, UUID site, String name, String start, String end,
                                String breaksJson, String days) throws SQLException {
        return insert(c, """
                INSERT INTO shift_templates (site_id, name, start_local, end_local, break_windows, days_of_week, pay_policy_id)
                VALUES (?, ?, ?::time, ?::time, ?::jsonb, ?::smallint[], ?) RETURNING id
                """, site, name, start, end, breaksJson, days, POLICY);
    }

    public static void setDefaultTemplate(Connection c, UUID site, UUID template) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE sites SET default_template_id = ? WHERE id = ?")) {
            ps.setObject(1, template);
            ps.setObject(2, site);
            ps.executeUpdate();
        }
    }

    public static UUID employee(Connection c, UUID site, String no) throws SQLException {
        return insert(c, "INSERT INTO employees (company_id, employee_no, full_name, home_site_id) VALUES (?, ?, ?, ?) RETURNING id",
                COMPANY, no, "Karyawan " + no, site);
    }

    public static UUID dailyAssignment(Connection c, UUID employee, UUID template, LocalDate date) throws SQLException {
        return insert(c, "INSERT INTO shift_assignments (employee_id, template_id, civil_date) VALUES (?, ?, ?) RETURNING id",
                employee, template, date);
    }

    public static UUID rangeAssignment(Connection c, UUID employee, UUID template, LocalDate start, LocalDate end)
            throws SQLException {
        return insert(c, "INSERT INTO shift_assignments (employee_id, template_id, start_date, end_date) VALUES (?, ?, ?, ?) RETURNING id",
                employee, template, start, end);
    }

    public static UUID device(Connection c, UUID site, String vendor, String serial, String protocol) throws SQLException {
        return insert(c, """
                INSERT INTO devices (site_id, vendor, model, serial, protocol_family, timezone)
                VALUES (?, ?, 'uji', ?, ?, 'Asia/Jakarta') RETURNING id
                """, site, vendor, serial, protocol);
    }

    public static UUID insert(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    public static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }
}
