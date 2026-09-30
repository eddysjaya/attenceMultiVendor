package id.andalan.attendance.domain.pay;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Isi {@code pay_policies.config} yang dipakai mesin, sudah diurai. Angka BUKAN konstanta kode:
 * selalu datang dari baris kebijakan berversi. Hanya preset aktif yang dibawa.
 */
public record PayPolicy(
        UUID id,
        String name,
        int version,
        Set<DayOfWeek> weeklyRestDays,
        List<OtBracket> weekdayOt,
        List<OtBracket> weeklyRestOt,
        String publicHolidayRatePrefix,
        String regularRateCode,
        String overCapRateCode,
        Params params) {

    public PayPolicy {
        Objects.requireNonNull(id, "id");
        weeklyRestDays = Set.copyOf(weeklyRestDays);
        weekdayOt = requireOpenEnded(weekdayOt, "weekday_ot");
        weeklyRestOt = requireOpenEnded(weeklyRestOt, "weekly_rest_ot");
        Objects.requireNonNull(params, "params");
    }

    /** Parameter kebijakan (tabel "Parameter kebijakan" di dokumen desain). */
    public record Params(
            int inGraceMinutes,
            int outGraceMinutes,
            int inLateLimitMinutes,
            int lateToleranceMinutes,
            int minSpanBeforeOutMinutes,
            int duplicatePunchSeconds,
            int continuousThresholdMinutes,
            double minRestHours,
            int truncationBufferMinutes,
            boolean countEarlyInAsWork,
            int quotaPreShiftMaxMinutes,
            int quotaMinBlockMinutes,
            int maxQuotaPerDayMinutes,
            String retroactiveApproval,
            int unquotedOtNoticeMinutes,
            boolean deductScheduledUnpaidBreak,
            int maxSessionSpanHours,
            int dailyOtCapMinutes,
            int weeklyOtCapMinutes,
            int maxClockSkewSeconds) {

        public Params {
            if (quotaMinBlockMinutes < 0 || quotaMinBlockMinutes > 240) {
                throw new IllegalArgumentException("quota_min_block_minutes di luar 0–240: " + quotaMinBlockMinutes);
            }
            if (quotaPreShiftMaxMinutes < 0 || quotaPreShiftMaxMinutes > 720) {
                throw new IllegalArgumentException("quota_pre_shift_max_minutes di luar 0–720: " + quotaPreShiftMaxMinutes);
            }
            if (dailyOtCapMinutes < 0 || weeklyOtCapMinutes < 0) {
                throw new IllegalArgumentException("cap lembur negatif");
            }
        }
    }

    /** Braket untuk jenis hari; libur nasional menyalin braket istirahat mingguan dengan awalan kode berbeda. */
    public List<OtBracket> bracketsFor(DayType dayType) {
        return switch (dayType) {
            case WEEKDAY -> weekdayOt;
            case WEEKLY_REST -> weeklyRestOt;
            case PUBLIC_HOLIDAY -> weeklyRestOt.stream()
                    .map(b -> new OtBracket(b.widthMinutes(), b.multiplier(), holidayCode(b.rateCode())))
                    .toList();
        };
    }

    private String holidayCode(String restCode) {
        // OT_REST_2_0 -> OT_HOL_2_0
        int i = restCode.indexOf('_', restCode.indexOf('_') + 1);
        return i < 0 ? publicHolidayRatePrefix + "_" + restCode : publicHolidayRatePrefix + restCode.substring(i);
    }

    private static List<OtBracket> requireOpenEnded(List<OtBracket> brackets, String name) {
        if (brackets.isEmpty() || brackets.getLast().widthMinutes() != null) {
            throw new IllegalArgumentException(name + " harus diakhiri braket tanpa batas (upto_minutes null)");
        }
        for (int i = 0; i < brackets.size() - 1; i++) {
            if (brackets.get(i).widthMinutes() == null) {
                throw new IllegalArgumentException(name + ": hanya braket terakhir yang boleh tanpa batas");
            }
        }
        return List.copyOf(brackets);
    }
}
