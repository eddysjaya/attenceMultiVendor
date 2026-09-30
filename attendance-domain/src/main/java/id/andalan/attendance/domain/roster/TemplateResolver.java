package id.andalan.attendance.domain.roster;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code resolve_template} dari dokumen desain. Urutan prioritas ketat:
 * <ol>
 *   <li>override harian ({@code civilDate = d}) — selalu menang, mengabaikan hari kerja template;</li>
 *   <li>rentang yang mencakup {@code d}; {@code startDate} terbaru menang, seri → {@link TemplateResolution.Ambiguous}.
 *       Template pemenang hanya berlaku pada hari kerjanya sendiri; di luar itu tidak ada shift
 *       (tidak jatuh ke template default);</li>
 *   <li>template default situs bila milik situs yang sama dan hari kerjanya cocok;</li>
 *   <li>selain itu tidak ada shift.</li>
 * </ol>
 */
public final class TemplateResolver {

    private TemplateResolver() {
    }

    /**
     * @param assignments semua assignment milik karyawan ini (boleh berisi tanggal lain)
     * @param templates   template yang mungkin dirujuk, per id
     */
    public static TemplateResolution resolve(
            LocalDate date,
            List<ShiftAssignment> assignments,
            Map<UUID, ShiftTemplate> templates,
            SiteRoster site) {

        List<ShiftAssignment> overrides = assignments.stream()
                .filter(a -> date.equals(a.civilDate()))
                .toList();
        if (overrides.size() > 1) {
            // Dicegah unique index parsial; tetap tidak menebak bila data rusak.
            return new TemplateResolution.Ambiguous(overrides.stream().map(ShiftAssignment::id).toList());
        }
        if (overrides.size() == 1) {
            ShiftAssignment o = overrides.getFirst();
            return new TemplateResolution.Resolved(template(templates, o.templateId()), o.id());
        }

        List<ShiftAssignment> ranges = assignments.stream().filter(a -> a.rangeCovers(date)).toList();
        if (!ranges.isEmpty()) {
            LocalDate latestStart = ranges.stream()
                    .map(ShiftAssignment::startDate)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            List<ShiftAssignment> winners = ranges.stream()
                    .filter(a -> a.startDate().equals(latestStart))
                    .toList();
            if (winners.size() > 1) {
                return new TemplateResolution.Ambiguous(winners.stream().map(ShiftAssignment::id).toList());
            }
            ShiftAssignment winner = winners.getFirst();
            ShiftTemplate t = template(templates, winner.templateId());
            return t.runsOn(date.getDayOfWeek())
                    ? new TemplateResolution.Resolved(t, winner.id())
                    : new TemplateResolution.NoShift("hari " + date.getDayOfWeek() + " bukan hari kerja template " + t.name());
        }

        if (site.defaultTemplateId() != null) {
            ShiftTemplate t = templates.get(site.defaultTemplateId());
            if (t != null && t.siteId().equals(site.siteId()) && t.runsOn(date.getDayOfWeek())) {
                return new TemplateResolution.Resolved(t, null);
            }
        }
        return new TemplateResolution.NoShift("tidak ada assignment dan template default tidak berlaku");
    }

    private static ShiftTemplate template(Map<UUID, ShiftTemplate> templates, UUID id) {
        ShiftTemplate t = templates.get(id);
        if (t == null) {
            throw new IllegalStateException("assignment merujuk template yang tidak dimuat: " + id);
        }
        return t;
    }
}
