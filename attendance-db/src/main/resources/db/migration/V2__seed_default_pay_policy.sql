-- Seed awal: satu company (v1 satu company, banyak situs) dan PayPolicy pp35-default-2026 versi 1.
-- Angka BUKAN nasihat hukum. Nilai rev 4: min_rest_hours = 1; parameter kuota diatur manual HR.
-- ID tetap supaya lingkungan dev dan uji bisa merujuknya.

INSERT INTO companies (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default');

INSERT INTO pay_policies (id, company_id, name, version, effective_from, config)
VALUES ('00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000001',
        'pp35-default-2026', 1, DATE '2026-01-01',
        $json${
  "dow_numbering": "iso8601",
  "active_preset": "five_day",
  "presets": {
    "five_day": {
      "weekly_rest_dows": [6, 7],
      "weekday_ot": [
        {"upto_minutes": 60, "multiplier": 1.5, "rate_code": "OT_WD_1_5"},
        {"upto_minutes": null, "multiplier": 2.0, "rate_code": "OT_WD_2_0"}
      ],
      "weekly_rest_ot": [
        {"upto_minutes": 480, "multiplier": 2.0, "rate_code": "OT_REST_2_0"},
        {"upto_minutes": 60, "multiplier": 3.0, "rate_code": "OT_REST_3_0"},
        {"upto_minutes": null, "multiplier": 4.0, "rate_code": "OT_REST_4_0"}
      ]
    },
    "six_day": {
      "weekly_rest_dows": [7],
      "weekday_ot": [
        {"upto_minutes": 60, "multiplier": 1.5, "rate_code": "OT_WD_1_5"},
        {"upto_minutes": null, "multiplier": 2.0, "rate_code": "OT_WD_2_0"}
      ],
      "weekly_rest_ot": [
        {"upto_minutes": 420, "multiplier": 2.0, "rate_code": "OT_REST_2_0"},
        {"upto_minutes": 60, "multiplier": 3.0, "rate_code": "OT_REST_3_0"},
        {"upto_minutes": null, "multiplier": 4.0, "rate_code": "OT_REST_4_0"}
      ]
    }
  },
  "public_holiday_ot": "same_as_weekly_rest",
  "public_holiday_rate_prefix": "OT_HOL",
  "regular_rate_code": "REG",
  "over_cap_rate_code": "OT_OVER_CAP",
  "rest_day_treatment": "full_day_premium",
  "params": {
    "in_grace_minutes": 15,
    "out_grace_minutes": 30,
    "in_late_limit_minutes": 180,
    "late_tolerance_minutes": 0,
    "min_span_before_out_minutes": 30,
    "duplicate_punch_seconds": 120,
    "continuous_threshold_minutes": 60,
    "min_rest_hours": 1,
    "min_rest_action": "warn",
    "truncation_buffer_minutes": 30,
    "count_early_in_as_work": false,
    "quota_pre_shift_max_minutes": 240,
    "quota_min_block_minutes": 30,
    "max_quota_per_day_minutes": 960,
    "retroactive_approval": "deny",
    "unquoted_ot_notice_minutes": 15,
    "deduct_scheduled_unpaid_break": true,
    "split_rate_at_civil_midnight": "on_day_type_change",
    "max_session_span_hours": 20,
    "daily_ot_cap_minutes": 240,
    "weekly_ot_cap_minutes": 1080,
    "cap_action": "segment_and_exception",
    "max_clock_skew_seconds": 30,
    "week_start": "monday"
  }
}$json$::jsonb);
