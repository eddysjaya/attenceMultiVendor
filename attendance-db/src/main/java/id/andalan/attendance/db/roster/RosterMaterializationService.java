package id.andalan.attendance.db.roster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import id.andalan.attendance.db.ExceptionKeys;
import id.andalan.attendance.domain.roster.BreakWindow;
import id.andalan.attendance.domain.roster.RosterMaterializer;
import id.andalan.attendance.domain.roster.ShiftAssignment;
import id.andalan.attendance.domain.roster.ShiftInstance;
import id.andalan.attendance.domain.roster.ShiftTemplate;
import id.andalan.attendance.domain.roster.SiteRoster;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Menulis hasil {@link RosterMaterializer} ke {@code shift_instances} secara idempoten, dalam satu transaksi:
 * upsert instance yang berubah, hapus instance yang tidak lagi dihasilkan, catat {@code roster_ambiguous},
 * dan antrekan rematch ({@code match_jobs}) untuk karyawan yang jadwalnya berubah.
 *
 * <p>Dipanggil job malam situs, API HR saat roster berubah, dan impor CSV pilot.
 */
public final class RosterMaterializationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public RosterMaterializationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Report(
            int inserted,
            int updated,
            int unchanged,
            int deleted,
            int ambiguous,
            int exceptionsObsoleted,
            Map<UUID, LocalDate> rematchEnqueued) {
    }

    /**
     * @param employeeIds null = semua karyawan aktif dengan {@code home_site_id} situs ini
     */
    public Report materialize(UUID siteId, LocalDate from, LocalDate to, Collection<UUID> employeeIds) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Report report = materialize(c, siteId, from, to, employeeIds);
                c.commit();
                return report;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private Report materialize(Connection c, UUID siteId, LocalDate from, LocalDate to, Collection<UUID> requested)
            throws SQLException {
        // Satu materialisasi per situs pada satu waktu.
        try (var ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('roster:' || ?))")) {
            ps.setString(1, siteId.toString());
            ps.execute();
        }

        SiteRoster site = loadSite(c, siteId);
        List<UUID> employees = requested != null ? List.copyOf(new HashSet<>(requested)) : activeEmployees(c, siteId);
        Map<UUID, List<ShiftAssignment>> assignments = loadAssignments(c, employees, from, to);
        Map<UUID, ShiftTemplate> templates = loadTemplates(c, siteId, assignments);

        RosterMaterializer.Result result =
                RosterMaterializer.materialize(site, employees, from, to, templates, assignments);

        Map<UUID, LocalDate> affected = new TreeMap<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (ShiftInstance i : result.instances()) {
            switch (upsertInstance(c, i)) {
                case INSERTED -> {
                    inserted++;
                    affected.merge(i.employeeId(), i.attendanceDate(), RosterMaterializationService::min);
                }
                case UPDATED -> {
                    updated++;
                    affected.merge(i.employeeId(), i.attendanceDate(), RosterMaterializationService::min);
                }
                case UNCHANGED -> unchanged++;
            }
        }

        int deleted = deleteStale(c, employees, from, to, result.instances(), affected);

        List<String> ambiguityKeys = new ArrayList<>();
        for (RosterMaterializer.Ambiguity a : result.ambiguities()) {
            ambiguityKeys.add(upsertAmbiguity(c, a));
        }
        int obsoleted = obsoleteResolvedAmbiguities(c, employees, from, to, ambiguityKeys);

        for (var e : affected.entrySet()) {
            enqueueRematch(c, e.getKey(), e.getValue());
        }
        return new Report(inserted, updated, unchanged, deleted, result.ambiguities().size(), obsoleted,
                Map.copyOf(affected));
    }

    // ------------------------------------------------------------------ baca

    private static SiteRoster loadSite(Connection c, UUID siteId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT timezone, default_template_id FROM sites WHERE id = ?")) {
            ps.setObject(1, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("situs tidak ditemukan: " + siteId);
                }
                return new SiteRoster(siteId, ZoneId.of(rs.getString(1)), rs.getObject(2, UUID.class));
            }
        }
    }

    private static List<UUID> activeEmployees(Connection c, UUID siteId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (var ps = c.prepareStatement(
                "SELECT id FROM employees WHERE home_site_id = ? AND status = 'active' ORDER BY id")) {
            ps.setObject(1, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    private static Map<UUID, List<ShiftAssignment>> loadAssignments(
            Connection c, List<UUID> employees, LocalDate from, LocalDate to) throws SQLException {
        Map<UUID, List<ShiftAssignment>> byEmployee = new HashMap<>();
        if (employees.isEmpty()) {
            return byEmployee;
        }
        String sql = """
                SELECT id, employee_id, template_id, civil_date, start_date, end_date
                FROM shift_assignments
                WHERE employee_id = ANY (?::uuid[])
                  AND ((civil_date BETWEEN ? AND ?)
                    OR (civil_date IS NULL AND start_date <= ? AND (end_date IS NULL OR end_date >= ?)))
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setArray(1, uuidArray(c, employees));
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setObject(4, to);
            ps.setObject(5, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var a = new ShiftAssignment(
                            rs.getObject("id", UUID.class),
                            rs.getObject("employee_id", UUID.class),
                            rs.getObject("template_id", UUID.class),
                            rs.getObject("civil_date", LocalDate.class),
                            rs.getObject("start_date", LocalDate.class),
                            rs.getObject("end_date", LocalDate.class));
                    byEmployee.computeIfAbsent(a.employeeId(), k -> new ArrayList<>()).add(a);
                }
            }
        }
        return byEmployee;
    }

    /** Template situs ini, ditambah template lain yang dirujuk assignment (agar tidak gagal diam-diam). */
    private static Map<UUID, ShiftTemplate> loadTemplates(
            Connection c, UUID siteId, Map<UUID, List<ShiftAssignment>> assignments) throws SQLException {
        Set<UUID> referenced = new HashSet<>();
        assignments.values().forEach(list -> list.forEach(a -> referenced.add(a.templateId())));
        String sql = """
                SELECT id, site_id, name, start_local, end_local, break_windows::text, days_of_week, pay_policy_id
                FROM shift_templates
                WHERE site_id = ? OR id = ANY (?::uuid[])
                """;
        Map<UUID, ShiftTemplate> templates = new LinkedHashMap<>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, siteId);
            ps.setArray(2, uuidArray(c, referenced));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                    for (Object d : (Object[]) rs.getArray("days_of_week").getArray()) {
                        days.add(DayOfWeek.of(((Number) d).intValue()));
                    }
                    var t = new ShiftTemplate(
                            rs.getObject("id", UUID.class),
                            rs.getObject("site_id", UUID.class),
                            rs.getString("name"),
                            rs.getObject("start_local", LocalTime.class),
                            rs.getObject("end_local", LocalTime.class),
                            parseBreaks(rs.getString("break_windows")),
                            days,
                            rs.getObject("pay_policy_id", UUID.class));
                    templates.put(t.id(), t);
                }
            }
        }
        return templates;
    }

    static List<BreakWindow> parseBreaks(String json) {
        try {
            List<BreakWindow> breaks = new ArrayList<>();
            for (JsonNode n : JSON.readTree(json)) {
                breaks.add(new BreakWindow(
                        LocalTime.parse(n.path("start").asText()),
                        LocalTime.parse(n.path("end").asText()),
                        n.path("paid").asBoolean(false)));
            }
            return breaks;
        } catch (Exception e) {
            throw new IllegalStateException("break_windows tidak valid: " + json, e);
        }
    }

    // ------------------------------------------------------------------ tulis

    private enum Upsert { INSERTED, UPDATED, UNCHANGED }

    private static Upsert upsertInstance(Connection c, ShiftInstance i) throws SQLException {
        String sql = """
                INSERT INTO shift_instances (employee_id, template_id, source_assignment_id, attendance_date,
                                             timezone, scheduled_start, scheduled_end, break_windows)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (employee_id, attendance_date, template_id) DO UPDATE
                    SET source_assignment_id = EXCLUDED.source_assignment_id,
                        timezone             = EXCLUDED.timezone,
                        scheduled_start      = EXCLUDED.scheduled_start,
                        scheduled_end        = EXCLUDED.scheduled_end,
                        break_windows        = EXCLUDED.break_windows,
                        materialized_at      = now()
                    WHERE (shift_instances.source_assignment_id, shift_instances.timezone, shift_instances.scheduled_start,
                           shift_instances.scheduled_end, shift_instances.break_windows)
                          IS DISTINCT FROM
                          (EXCLUDED.source_assignment_id, EXCLUDED.timezone, EXCLUDED.scheduled_start,
                           EXCLUDED.scheduled_end, EXCLUDED.break_windows)
                RETURNING (xmax = 0) AS inserted
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, i.employeeId());
            ps.setObject(2, i.templateId());
            ps.setObject(3, i.sourceAssignmentId());
            ps.setObject(4, i.attendanceDate());
            ps.setString(5, i.zone().getId());
            ps.setObject(6, OffsetDateTime.ofInstant(i.scheduledStart(), ZoneOffset.UTC));
            ps.setObject(7, OffsetDateTime.ofInstant(i.scheduledEnd(), ZoneOffset.UTC));
            ps.setString(8, breaksJson(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Upsert.UNCHANGED;
                }
                return rs.getBoolean(1) ? Upsert.INSERTED : Upsert.UPDATED;
            }
        }
    }

    private static String breaksJson(ShiftInstance i) {
        ArrayNode arr = JSON.createArrayNode();
        for (ShiftInstance.ScheduledBreak b : i.breaks()) {
            arr.addObject()
                    .put("start", b.start().toString())
                    .put("end", b.end().toString())
                    .put("paid", b.paid());
        }
        return arr.toString();
    }

    private static int deleteStale(Connection c, List<UUID> employees, LocalDate from, LocalDate to,
                                   List<ShiftInstance> keep, Map<UUID, LocalDate> affected) throws SQLException {
        if (employees.isEmpty()) {
            return 0;
        }
        String sql = """
                DELETE FROM shift_instances si
                WHERE si.employee_id = ANY (?::uuid[])
                  AND si.attendance_date BETWEEN ? AND ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM unnest(?::uuid[], ?::date[], ?::uuid[]) AS k(employee_id, attendance_date, template_id)
                      WHERE k.employee_id = si.employee_id
                        AND k.attendance_date = si.attendance_date
                        AND k.template_id = si.template_id)
                RETURNING si.employee_id, si.attendance_date
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setArray(1, uuidArray(c, employees));
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setArray(4, uuidArray(c, keep.stream().map(ShiftInstance::employeeId).toList()));
            ps.setArray(5, c.createArrayOf("date", keep.stream().map(i -> i.attendanceDate().toString()).toArray()));
            ps.setArray(6, uuidArray(c, keep.stream().map(ShiftInstance::templateId).toList()));
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    affected.merge(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                            RosterMaterializationService::min);
                }
            }
            return n;
        }
    }

    private static String upsertAmbiguity(Connection c, RosterMaterializer.Ambiguity a) throws SQLException {
        String key = ExceptionKeys.of("roster_ambiguous", a.employeeId(), a.date(), a.assignmentIds());
        var detail = JSON.createObjectNode();
        var ids = detail.putArray("assignment_ids");
        a.assignmentIds().forEach(id -> ids.add(id.toString()));
        String sql = """
                INSERT INTO attendance_exceptions (employee_id, attendance_date, run_mode, exception_key,
                                                   code, severity, detail)
                VALUES (?, ?, NULL, ?, 'roster_ambiguous', 'error', ?::jsonb)
                ON CONFLICT (exception_key) DO UPDATE
                    SET detail     = EXCLUDED.detail,
                        updated_at = now(),
                        status     = CASE WHEN attendance_exceptions.status = 'obsolete'
                                          THEN 'open' ELSE attendance_exceptions.status END
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, a.employeeId());
            ps.setObject(2, a.date());
            ps.setString(3, key);
            ps.setString(4, detail.toString());
            ps.executeUpdate();
        }
        return key;
    }

    /** {@code roster_ambiguous} yang masih open di rentang ini tetapi tidak lagi dihasilkan → obsolete. */
    private static int obsoleteResolvedAmbiguities(Connection c, List<UUID> employees, LocalDate from, LocalDate to,
                                                   List<String> stillAmbiguous) throws SQLException {
        if (employees.isEmpty()) {
            return 0;
        }
        String sql = """
                UPDATE attendance_exceptions
                SET status = 'obsolete', updated_at = now()
                WHERE code = 'roster_ambiguous'
                  AND status = 'open'
                  AND employee_id = ANY (?::uuid[])
                  AND attendance_date BETWEEN ? AND ?
                  AND exception_key <> ALL (?::text[])
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setArray(1, uuidArray(c, employees));
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setArray(4, c.createArrayOf("text", stillAmbiguous.toArray()));
            return ps.executeUpdate();
        }
    }

    private static void enqueueRematch(Connection c, UUID employee, LocalDate from) throws SQLException {
        String sql = """
                INSERT INTO match_jobs (employee_id, generation, rematch_from)
                VALUES (?, 1, ?)
                ON CONFLICT (employee_id) DO UPDATE
                    SET generation   = match_jobs.generation + 1,
                        rematch_from = LEAST(match_jobs.rematch_from, EXCLUDED.rematch_from),
                        enqueued_at  = now()
                """;
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, employee);
            ps.setObject(2, from);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ util

    private static Array uuidArray(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.stream().map(UUID::toString).toArray());
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
