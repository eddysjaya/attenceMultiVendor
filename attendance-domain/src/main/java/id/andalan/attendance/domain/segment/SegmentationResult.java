package id.andalan.attendance.domain.segment;

import java.util.List;
import java.util.Map;

/**
 * Keluaran segmenter.
 *
 * @param otMinutes    total menit {@code kind = OT} (termasuk OT_OVER_CAP), untuk penghitung cap harian/pekan
 * @param quotaUsed    total kuota terpakai setelah sesi ini (sudah termasuk {@code quotaUsedAlready})
 */
public record SegmentationResult(List<Segment> segments, List<Issue> issues, int otMinutes, int quotaUsed) {

    public SegmentationResult {
        segments = List.copyOf(segments);
        issues = List.copyOf(issues);
    }

    /** Exception yang dihasilkan segmenter, mis. {@code daily_ot_cap_exceeded} dengan {@code minutes}. */
    public record Issue(String code, String severity, Map<String, Integer> detail) {
        public Issue {
            detail = Map.copyOf(detail);
        }
    }

    public int minutes(Segment.Kind kind) {
        return segments.stream().filter(s -> s.kind() == kind).mapToInt(Segment::minutes).sum();
    }

    public int minutes(String rateCode) {
        return segments.stream().filter(s -> s.rateCode().equals(rateCode)).mapToInt(Segment::minutes).sum();
    }
}
