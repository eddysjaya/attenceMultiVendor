package id.andalan.attendance.domain.roster;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code materialize_roster} dari dokumen desain: fungsi murni dari assignment + template
 * menjadi {@link ShiftInstance}. Penulisan ke basis data ada di modul {@code attendance-db}.
 */
public final class RosterMaterializer {

    /** Batas rentang satu panggilan, supaya job malam atau API tidak memuat berbulan-bulan sekaligus. */
    public static final int MAX_DAYS = 62;

    private RosterMaterializer() {
    }

    public record Ambiguity(UUID employeeId, LocalDate date, List<UUID> assignmentIds) {
    }

    public record Result(List<ShiftInstance> instances, List<Ambiguity> ambiguities) {
    }

    public static Result materialize(
            SiteRoster site,
            Collection<UUID> employeeIds,
            LocalDate from,
            LocalDate to,
            Map<UUID, ShiftTemplate> templates,
            Map<UUID, List<ShiftAssignment>> assignmentsByEmployee) {

        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to sebelum from: " + from + " .. " + to);
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("rentang lebih dari " + MAX_DAYS + " hari");
        }

        List<ShiftInstance> instances = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        for (UUID employee : employeeIds.stream().sorted().toList()) {
            List<ShiftAssignment> assignments = assignmentsByEmployee.getOrDefault(employee, List.of());
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                switch (TemplateResolver.resolve(d, assignments, templates, site)) {
                    case TemplateResolution.Resolved r ->
                            instances.add(instanceOf(employee, d, r.template(), r.sourceAssignmentId(), site.zone()));
                    case TemplateResolution.Ambiguous a ->
                            ambiguities.add(new Ambiguity(employee, d, a.assignmentIds()));
                    case TemplateResolution.NoShift _ -> {
                        // bukan exception: memang tidak ada shift hari itu
                    }
                }
            }
        }
        return new Result(List.copyOf(instances), List.copyOf(ambiguities));
    }

    static ShiftInstance instanceOf(UUID employee, LocalDate d, ShiftTemplate t, UUID sourceAssignmentId, ZoneId zone) {
        LocalDate endDate = t.crossesMidnight() ? d.plusDays(1) : d;
        var start = d.atTime(t.startLocal()).atZone(zone).toInstant();
        var end = endDate.atTime(t.endLocal()).atZone(zone).toInstant();

        List<ShiftInstance.ScheduledBreak> breaks = new ArrayList<>();
        for (BreakWindow b : t.breaks()) {
            // Pada shift malam, jam istirahat sebelum jam mulai berarti hari berikutnya.
            LocalDate breakDate = t.crossesMidnight() && b.start().isBefore(t.startLocal()) ? d.plusDays(1) : d;
            LocalDate breakEndDate = b.end().isAfter(b.start()) ? breakDate : breakDate.plusDays(1);
            var bs = breakDate.atTime(b.start()).atZone(zone).toInstant();
            var be = breakEndDate.atTime(b.end()).atZone(zone).toInstant();
            if (bs.isBefore(start) || be.isAfter(end)) {
                throw new IllegalArgumentException("istirahat " + b.start() + "-" + b.end()
                        + " di luar shift " + t.name() + " " + t.startLocal() + "-" + t.endLocal());
            }
            breaks.add(new ShiftInstance.ScheduledBreak(bs, be, b.paid()));
        }
        return new ShiftInstance(employee, t.id(), sourceAssignmentId, d, zone, start, end, breaks);
    }
}
