package id.andalan.attendance.db.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.andalan.attendance.domain.pay.OtBracket;
import id.andalan.attendance.domain.pay.PayPolicy;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mengurai {@code pay_policies.config} menjadi {@link PayPolicy}. Ketat: kunci wajib yang hilang atau nilai
 * yang belum didukung mesin adalah error, bukan default diam-diam.
 */
public final class PayPolicyJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PayPolicyJson() {
    }

    public static PayPolicy parse(UUID id, String name, int version, String configJson) {
        JsonNode root;
        try {
            root = JSON.readTree(configJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("config PayPolicy bukan JSON valid", e);
        }
        require(text(root, "dow_numbering").equals("iso8601"), "dow_numbering harus iso8601");
        require(text(root, "rest_day_treatment").equals("full_day_premium"),
                "rest_day_treatment '" + text(root, "rest_day_treatment") + "' belum didukung mesin v1");
        require(text(root, "public_holiday_ot").equals("same_as_weekly_rest"),
                "public_holiday_ot '" + text(root, "public_holiday_ot") + "' belum didukung mesin v1");

        String presetName = text(root, "active_preset");
        JsonNode preset = node(node(root, "presets"), presetName);
        JsonNode params = node(root, "params");

        return new PayPolicy(
                id, name, version,
                days(node(preset, "weekly_rest_dows")),
                brackets(node(preset, "weekday_ot")),
                brackets(node(preset, "weekly_rest_ot")),
                text(root, "public_holiday_rate_prefix"),
                text(root, "regular_rate_code"),
                text(root, "over_cap_rate_code"),
                new PayPolicy.Params(
                        integer(params, "in_grace_minutes"),
                        integer(params, "out_grace_minutes"),
                        integer(params, "in_late_limit_minutes"),
                        integer(params, "late_tolerance_minutes"),
                        integer(params, "min_span_before_out_minutes"),
                        integer(params, "duplicate_punch_seconds"),
                        integer(params, "continuous_threshold_minutes"),
                        node(params, "min_rest_hours").asDouble(),
                        integer(params, "truncation_buffer_minutes"),
                        bool(params, "count_early_in_as_work"),
                        integer(params, "quota_pre_shift_max_minutes"),
                        integer(params, "quota_min_block_minutes"),
                        integer(params, "max_quota_per_day_minutes"),
                        text(params, "retroactive_approval"),
                        integer(params, "unquoted_ot_notice_minutes"),
                        bool(params, "deduct_scheduled_unpaid_break"),
                        integer(params, "max_session_span_hours"),
                        integer(params, "daily_ot_cap_minutes"),
                        integer(params, "weekly_ot_cap_minutes"),
                        integer(params, "max_clock_skew_seconds")));
    }

    private static Set<DayOfWeek> days(JsonNode arr) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (JsonNode d : arr) {
            int iso = d.asInt();
            require(iso >= 1 && iso <= 7, "weekly_rest_dows memakai ISO-8601 1..7, bukan " + iso);
            days.add(DayOfWeek.of(iso));
        }
        return days;
    }

    private static List<OtBracket> brackets(JsonNode arr) {
        List<OtBracket> list = new ArrayList<>();
        for (JsonNode b : arr) {
            JsonNode upto = node(b, "upto_minutes");
            list.add(new OtBracket(upto.isNull() ? null : upto.asInt(),
                    new BigDecimal(node(b, "multiplier").asText()), text(b, "rate_code")));
        }
        return list;
    }

    private static JsonNode node(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        require(n != null, "kunci wajib hilang: " + field);
        return n;
    }

    private static String text(JsonNode parent, String field) {
        return node(parent, field).asText();
    }

    private static int integer(JsonNode parent, String field) {
        JsonNode n = node(parent, field);
        require(n.isIntegralNumber(), field + " harus bilangan bulat");
        return n.asInt();
    }

    private static boolean bool(JsonNode parent, String field) {
        JsonNode n = node(parent, field);
        require(n.isBoolean(), field + " harus boolean");
        return n.asBoolean();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
