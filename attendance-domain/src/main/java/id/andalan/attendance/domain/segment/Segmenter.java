package id.andalan.attendance.domain.segment;

import id.andalan.attendance.domain.pay.DayType;
import id.andalan.attendance.domain.pay.OtBracket;
import id.andalan.attendance.domain.pay.PayPolicy;
import id.andalan.attendance.domain.pay.WorkCalendar;
import id.andalan.attendance.domain.roster.ShiftInstance;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@code segment()} dari dokumen desain (rev 3, lembur kuota). Fungsi murni: sesi {@code closed} + kebijakan
 * + kalender + penghitung yang dibawa dari sesi sebelumnya → segmen bayar dan exception.
 *
 * <p>Aturan inti:
 * <ul>
 *   <li>istirahat terjadwal tak dibayar → {@code UNPAID_BREAK}; di dalam shift hari kerja → {@code REG};
 *       di dalam shift hari istirahat/libur → lembur premium tanpa kuota (roster adalah persetujuannya);</li>
 *   <li>di luar shift, menit menjadi lembur hanya bila lolos blok minimum, setelah {@code effectiveFrom} order,
 *       di dalam batas pra-shift, dan kuota masih tersisa; kuota dipakai kronologis, FIFO per order;</li>
 *   <li>tengah malam selalu memotong segmen; braket di-reset hanya bila jenis hari berubah;
 *       cap harian/pekan tidak di-reset di tengah malam, dan kelebihan menjadi {@code OT_OVER_CAP}, tidak dibuang.</li>
 * </ul>
 */
public final class Segmenter {

    public static final String UNPAID_BREAK = "UNPAID_BREAK";
    public static final String UNPAID_EARLY = "UNPAID_EARLY";
    public static final String UNPAID_BELOW_MIN_BLOCK = "UNPAID_BELOW_MIN_BLOCK";
    public static final String UNPAID_BEFORE_APPROVAL = "UNPAID_BEFORE_APPROVAL";
    public static final String UNPAID_NO_QUOTA = "UNPAID_NO_QUOTA";
    public static final String UNPAID_OVER_QUOTA = "UNPAID_OVER_QUOTA";

    private Segmenter() {
    }

    public static SegmentationResult segment(
            ClosedSession session,
            PayPolicy policy,
            WorkCalendar calendar,
            int quotaUsedAlready,
            int dailyOtAlready,
            int weeklyOtAlready) {
        return new Run(session, policy, calendar, quotaUsedAlready, dailyOtAlready, weeklyOtAlready).execute();
    }

    private enum Base { BREAK, REGULAR, PREMIUM_SCHEDULED, OUTSIDE }

    private record Slice(Instant start, Instant end, Base base, boolean preShift, boolean beforePreShiftLimit) {
        int minutes() {
            return minutesBetween(start, end);
        }
    }

    /** Satu eksekusi dengan state berjalan; tidak dibagi antar panggilan. */
    private static final class Run {
        private final ClosedSession s;
        private final PayPolicy policy;
        private final PayPolicy.Params p;
        private final WorkCalendar calendar;
        private final List<Segment> out = new ArrayList<>();

        private int used;
        private int daily;
        private int weekly;
        private int bracketOffset;
        private DayType currentDayType;
        private int overCapDaily;
        private int overCapWeekly;
        private int unquotedBeforeApproval;
        private int unquotedNoQuota;
        private int unquotedOverQuota;
        private String currentOrigin = "";
        private String lastOrigin = "";

        Run(ClosedSession s, PayPolicy policy, WorkCalendar calendar, int quotaUsed, int dailyAlready, int weeklyAlready) {
            this.s = s;
            this.policy = policy;
            this.p = policy.params();
            this.calendar = calendar;
            this.used = quotaUsed;
            this.daily = dailyAlready;
            this.weekly = weeklyAlready;
            this.bracketOffset = dailyAlready; // braket berlanjut lintas sesi pada tanggal bisnis yang sama
        }

        SegmentationResult execute() {
            List<Slice> slices = slices();
            boolean[] belowMinBlock = belowMinBlock(slices);

            for (int i = 0; i < slices.size(); i++) {
                Slice sl = slices.get(i);
                currentOrigin = sl.base() == Base.OUTSIDE ? (sl.preShift() ? "pre" : "post") : sl.base().name();
                switch (sl.base()) {
                    case BREAK -> emit(sl.start(), sl.end(), Segment.Kind.BREAK, UNPAID_BREAK, null, null);
                    case REGULAR -> emit(sl.start(), sl.end(), Segment.Kind.REGULAR, policy.regularRateCode(), null, null);
                    case PREMIUM_SCHEDULED -> otWalk(sl.start(), sl.end(), null);
                    case OUTSIDE -> outside(sl, belowMinBlock[i]);
                }
            }

            List<SegmentationResult.Issue> issues = new ArrayList<>();
            if (overCapDaily > 0) {
                issues.add(new SegmentationResult.Issue("daily_ot_cap_exceeded", "warning", Map.of("minutes", overCapDaily)));
            }
            if (overCapWeekly > 0) {
                issues.add(new SegmentationResult.Issue("weekly_ot_cap_exceeded", "warning", Map.of("minutes", overCapWeekly)));
            }
            int unquoted = unquotedBeforeApproval + unquotedNoQuota + unquotedOverQuota;
            if (unquoted > 0 && unquoted >= p.unquotedOtNoticeMinutes()) {
                Map<String, Integer> detail = new LinkedHashMap<>();
                detail.put("minutes", unquoted);
                detail.put("before_approval", unquotedBeforeApproval);
                detail.put("no_quota", unquotedNoQuota);
                detail.put("over_quota", unquotedOverQuota);
                issues.add(new SegmentationResult.Issue("ot_without_quota", "info", detail));
            }
            int ot = out.stream().filter(x -> x.kind() == Segment.Kind.OT).mapToInt(Segment::minutes).sum();
            return new SegmentationResult(out, issues, ot, used);
        }

        // ------------------------------------------------------------ potongan struktural

        private List<Slice> slices() {
            Instant a0 = s.openedAt();
            Instant b0 = s.closedAt();
            TreeSet<Instant> cuts = new TreeSet<>(List.of(a0, b0));
            for (ShiftInstance shift : s.shifts()) {
                addInside(cuts, shift.scheduledStart(), a0, b0);
                addInside(cuts, shift.scheduledEnd(), a0, b0);
                for (ShiftInstance.ScheduledBreak br : shift.breaks()) {
                    if (deductedBreak(br)) {
                        addInside(cuts, br.start(), a0, b0);
                        addInside(cuts, br.end(), a0, b0);
                    }
                }
            }
            for (QuotaOrder o : s.orders()) {
                addInside(cuts, o.effectiveFrom(), a0, b0);
            }
            Instant preLimit = preShiftLimit();
            if (preLimit != null) {
                addInside(cuts, preLimit, a0, b0);
            }
            for (Instant t = nextMidnight(a0); t.isBefore(b0); t = nextMidnight(t)) {
                cuts.add(t);
            }

            List<Slice> slices = new ArrayList<>();
            Instant prev = null;
            for (Instant cut : cuts) {
                if (prev != null) {
                    slices.add(classify(prev, cut, preLimit));
                }
                prev = cut;
            }
            return slices;
        }

        private Slice classify(Instant a, Instant b, Instant preLimit) {
            Instant anchor = s.anchor();
            boolean pre = anchor != null && !b.isAfter(anchor);
            boolean beforeLimit = pre && preLimit != null && !b.isAfter(preLimit);
            for (ShiftInstance shift : s.shifts()) {
                for (ShiftInstance.ScheduledBreak br : shift.breaks()) {
                    if (deductedBreak(br) && !a.isBefore(br.start()) && !b.isAfter(br.end())) {
                        return new Slice(a, b, Base.BREAK, false, false);
                    }
                }
            }
            for (ShiftInstance shift : s.shifts()) {
                if (!a.isBefore(shift.scheduledStart()) && !b.isAfter(shift.scheduledEnd())) {
                    Base base = calendar.dayType(civilDate(a)).isRestDay() ? Base.PREMIUM_SCHEDULED : Base.REGULAR;
                    return new Slice(a, b, base, false, false);
                }
            }
            return new Slice(a, b, Base.OUTSIDE, pre, beforeLimit);
        }

        /** Batas paling awal kuota boleh dipakai sebelum shift: anchor − quota_pre_shift_max_minutes. */
        private Instant preShiftLimit() {
            Instant anchor = s.anchor();
            return anchor == null ? null : anchor.minus(Duration.ofMinutes(p.quotaPreShiftMaxMinutes()));
        }

        private boolean deductedBreak(ShiftInstance.ScheduledBreak br) {
            return !br.paid() && p.deductScheduledUnpaidBreak();
        }

        /** Rentang kerja fisik di luar shift yang lebih pendek dari blok minimum tidak memakan kuota. */
        private boolean[] belowMinBlock(List<Slice> slices) {
            boolean[] below = new boolean[slices.size()];
            int i = 0;
            while (i < slices.size()) {
                if (slices.get(i).base() != Base.OUTSIDE) {
                    i++;
                    continue;
                }
                int j = i;
                int total = 0;
                while (j < slices.size() && slices.get(j).base() == Base.OUTSIDE
                        && (j == i || slices.get(j - 1).end().equals(slices.get(j).start()))) {
                    total += slices.get(j).minutes();
                    j++;
                }
                if (total < p.quotaMinBlockMinutes()) {
                    for (int k = i; k < j; k++) {
                        below[k] = true;
                    }
                }
                i = j;
            }
            return below;
        }

        // ------------------------------------------------------------ di luar shift

        private void outside(Slice sl, boolean belowMinBlock) {
            if (sl.preShift() && (belowMinBlock || sl.beforePreShiftLimit())) {
                early(sl.start(), sl.end());
                return;
            }
            if (belowMinBlock) {
                emit(sl.start(), sl.end(), Segment.Kind.UNPAID, UNPAID_BELOW_MIN_BLOCK, null, null);
                return;
            }
            int pool = s.orders().stream()
                    .filter(o -> !o.effectiveFrom().isAfter(sl.start()))
                    .mapToInt(QuotaOrder::quotaMinutes)
                    .sum();
            if (pool == 0) {
                boolean approvedLater = s.orders().stream().anyMatch(o -> o.effectiveFrom().isAfter(sl.start()));
                if (approvedLater) {
                    emit(sl.start(), sl.end(), Segment.Kind.UNPAID, UNPAID_BEFORE_APPROVAL, null, null);
                    unquotedBeforeApproval += sl.minutes();
                } else if (sl.preShift()) {
                    early(sl.start(), sl.end());
                } else {
                    emit(sl.start(), sl.end(), Segment.Kind.UNPAID, UNPAID_NO_QUOTA, null, null);
                    unquotedNoQuota += sl.minutes();
                }
                return;
            }

            int take = Math.min(sl.minutes(), Math.max(0, pool - used));
            Instant cursor = sl.start();
            int remaining = take;
            while (remaining > 0) {
                int[] orderAndRoom = orderForUsedMinute();
                int chunk = Math.min(remaining, orderAndRoom[1]);
                Instant next = cursor.plus(Duration.ofMinutes(chunk));
                otWalk(cursor, next, s.orders().get(orderAndRoom[0]).id());
                used += chunk;
                remaining -= chunk;
                cursor = next;
            }
            if (cursor.isBefore(sl.end())) {
                emit(cursor, sl.end(), Segment.Kind.UNPAID, UNPAID_OVER_QUOTA, null, null);
                unquotedOverQuota += minutesBetween(cursor, sl.end());
            }
        }

        /** Menit pra-shift yang bukan lembur: tidak dibayar, kecuali kebijakan menghitungnya sebagai kerja. */
        private void early(Instant a, Instant b) {
            if (p.countEarlyInAsWork()) {
                emit(a, b, Segment.Kind.REGULAR, policy.regularRateCode(), null, null);
            } else {
                emit(a, b, Segment.Kind.UNPAID, UNPAID_EARLY, null, null);
            }
        }

        /** FIFO: menit kuota ke-{@code used} milik order mana, dan sisa kuota order itu. */
        private int[] orderForUsedMinute() {
            int cumulative = 0;
            for (int i = 0; i < s.orders().size(); i++) {
                cumulative += s.orders().get(i).quotaMinutes();
                if (used < cumulative) {
                    return new int[] {i, cumulative - used};
                }
            }
            throw new IllegalStateException("kuota habis tetapi masih dialokasikan");
        }

        // ------------------------------------------------------------ lembur: braket + cap

        private void otWalk(Instant a, Instant b, UUID orderId) {
            DayType dayType = calendar.dayType(civilDate(a));
            if (currentDayType != null && dayType != currentDayType) {
                bracketOffset = 0;
            }
            currentDayType = dayType;
            List<OtBracket> brackets = policy.bracketsFor(dayType);

            Instant cursor = a;
            while (cursor.isBefore(b)) {
                int left = minutesBetween(cursor, b);
                boolean dailyFull = daily >= p.dailyOtCapMinutes();
                boolean weeklyFull = weekly >= p.weeklyOtCapMinutes();
                int take;
                if (dailyFull || weeklyFull) {
                    take = left;
                    emit(cursor, cursor.plus(Duration.ofMinutes(take)), Segment.Kind.OT,
                            policy.overCapRateCode(), false, orderId);
                    if (dailyFull) {
                        overCapDaily += take;
                    }
                    if (weeklyFull) {
                        overCapWeekly += take;
                    }
                } else {
                    int[] bracket = bracketAt(brackets, bracketOffset);
                    take = Math.min(Math.min(left, bracket[1]),
                            Math.min(p.dailyOtCapMinutes() - daily, p.weeklyOtCapMinutes() - weekly));
                    emit(cursor, cursor.plus(Duration.ofMinutes(take)), Segment.Kind.OT,
                            brackets.get(bracket[0]).rateCode(), true, orderId);
                    bracketOffset += take;
                }
                daily += take;
                weekly += take;
                cursor = cursor.plus(Duration.ofMinutes(take));
            }
        }

        /** Indeks braket pada offset menit, dan sisa ruang di braket itu. */
        private static int[] bracketAt(List<OtBracket> brackets, int offset) {
            int cumulative = 0;
            for (int i = 0; i < brackets.size(); i++) {
                Integer width = brackets.get(i).widthMinutes();
                if (width == null) {
                    return new int[] {i, Integer.MAX_VALUE};
                }
                if (offset < cumulative + width) {
                    return new int[] {i, cumulative + width - offset};
                }
                cumulative += width;
            }
            throw new IllegalStateException("braket tanpa ujung terbuka");
        }

        // ------------------------------------------------------------ keluaran

        /**
         * Menambah segmen; menggabung dengan segmen sebelumnya hanya bila bersambung, identik, satu tanggal sipil,
         * dan berasal dari sumber yang sama (mis. menit pra-shift yang dihitung REG tidak dilebur ke REG shift,
         * supaya audit tetap melihat asal menitnya).
         */
        private void emit(Instant a, Instant b, Segment.Kind kind, String rate, Boolean withinCap, UUID orderId) {
            LocalDate civil = civilDate(a);
            String origin = currentOrigin;
            if (!out.isEmpty() && origin.equals(lastOrigin)) {
                Segment last = out.getLast();
                if (last.end().equals(a) && last.kind() == kind && last.rateCode().equals(rate)
                        && java.util.Objects.equals(last.withinCap(), withinCap)
                        && java.util.Objects.equals(last.overtimeOrderId(), orderId)
                        && last.civilDate().equals(civil)) {
                    out.set(out.size() - 1, new Segment(last.start(), b, last.minutes() + minutesBetween(a, b),
                            kind, s.attendanceDate(), civil, rate, withinCap, orderId));
                    return;
                }
            }
            out.add(new Segment(a, b, minutesBetween(a, b), kind, s.attendanceDate(), civil, rate, withinCap, orderId));
            lastOrigin = origin;
        }

        private LocalDate civilDate(Instant t) {
            return t.atZone(s.zone()).toLocalDate();
        }

        private Instant nextMidnight(Instant t) {
            return civilDate(t).plusDays(1).atStartOfDay(s.zone()).toInstant();
        }

        private static void addInside(TreeSet<Instant> cuts, Instant t, Instant a0, Instant b0) {
            if (t.isAfter(a0) && t.isBefore(b0)) {
                cuts.add(t);
            }
        }
    }

    static int minutesBetween(Instant a, Instant b) {
        return Math.toIntExact(Duration.between(a, b).toMinutes());
    }
}
