-- =====================================================================
-- Sistem Absensi Multi-Vendor — skema awal (PR 1a)
-- Sumber: Doc/desain-sistem-absensi-multi-vendor.md rev 5, bagian "Perubahan Model Data".
-- Semua waktu absolut = timestamptz (UTC di server). Waktu sipil perangkat = timestamp tanpa zona.
-- Enum ditulis sebagai text + CHECK, bukan tipe ENUM Postgres, supaya mudah dikembangkan.
-- =====================================================================

-- Validasi nama zona IANA: konversi ke zona yang tidak dikenal akan gagal.
CREATE FUNCTION is_valid_timezone(tz text) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE AS
$$
BEGIN
    PERFORM TIMESTAMPTZ '2000-01-01 00:00:00+00' AT TIME ZONE tz;
    RETURN tz IS NOT NULL AND tz <> '';
EXCEPTION WHEN others THEN
    RETURN false;
END;
$$;

-- ---------------------------------------------------------------------
-- Organisasi, kebijakan, situs
-- ---------------------------------------------------------------------
CREATE TABLE companies (
    id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL
);

CREATE TABLE pay_policies (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     uuid NOT NULL REFERENCES companies (id),
    name           text NOT NULL,
    version        int  NOT NULL CHECK (version > 0),
    effective_from date NOT NULL,
    config         jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_pay_policies_version UNIQUE (company_id, name, version),
    -- Pagar basis data untuk parameter yang diatur manual HR (rev 4). API juga memvalidasi (422).
    CONSTRAINT ck_pay_policies_config CHECK (
        config ->> 'dow_numbering' = 'iso8601'
        AND (config -> 'params' ->> 'quota_min_block_minutes')::int BETWEEN 0 AND 240
        AND (config -> 'params' ->> 'quota_pre_shift_max_minutes')::int BETWEEN 0 AND 720
        AND (config -> 'params' ->> 'min_rest_hours')::numeric >= 0
        AND (config -> 'params' ->> 'retroactive_approval') IN ('deny', 'admin_only')
    )
);
COMMENT ON TABLE pay_policies IS 'Baris tidak di-update di tempat; versi baru berlaku menurut effective_from.';

CREATE TABLE sites (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          uuid NOT NULL REFERENCES companies (id),
    name                text NOT NULL,
    timezone            text NOT NULL CHECK (is_valid_timezone(timezone)),
    default_template_id uuid NULL,
    flags               jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(flags) = 'object'),
    CONSTRAINT uq_sites_name UNIQUE (company_id, name)
);
COMMENT ON COLUMN sites.flags IS 'vendor_ingress_zk, vendor_ingress_hik, vendor_ingress_dahua, matcher_live, payroll_export; kunci absen = false';

CREATE TABLE shift_templates (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       uuid NOT NULL REFERENCES sites (id),
    name          text NOT NULL,
    start_local   time NOT NULL,
    end_local     time NOT NULL,
    break_windows jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(break_windows) = 'array'),
    days_of_week  smallint[] NOT NULL,
    pay_policy_id uuid NOT NULL REFERENCES pay_policies (id),
    CONSTRAINT uq_shift_templates_name UNIQUE (site_id, name),
    CONSTRAINT uq_shift_templates_id_site UNIQUE (id, site_id),
    -- ISO-8601: Senin = 1 ... Minggu = 7
    CONSTRAINT ck_shift_templates_dow CHECK (
        cardinality(days_of_week) > 0 AND days_of_week <@ ARRAY [1,2,3,4,5,6,7]::smallint[]
    ),
    CONSTRAINT ck_shift_templates_duration CHECK (start_local <> end_local)
);
COMMENT ON COLUMN shift_templates.end_local IS 'end_local <= start_local berarti shift berakhir hari sipil berikutnya';

-- Template default harus milik situs yang sama: dipaksakan lewat FK komposit, bukan di aplikasi saja.
ALTER TABLE sites
    ADD CONSTRAINT fk_sites_default_template
        FOREIGN KEY (default_template_id, id) REFERENCES shift_templates (id, site_id)
            ON DELETE SET NULL (default_template_id);

CREATE TABLE checkpoints (
    id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id uuid NOT NULL REFERENCES sites (id),
    name    text NOT NULL,
    CONSTRAINT uq_checkpoints_name UNIQUE (site_id, name)
);

-- ---------------------------------------------------------------------
-- Perangkat
-- ---------------------------------------------------------------------
CREATE TABLE devices (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id               uuid NOT NULL REFERENCES sites (id),
    checkpoint_id         uuid NULL REFERENCES checkpoints (id),
    vendor                text NOT NULL CHECK (vendor IN ('zkteco', 'hikvision', 'dahua')),
    model                 text NOT NULL,
    serial                text NOT NULL,
    ip                    inet NULL,
    protocol_family       text NOT NULL,
    reconcile_enabled     boolean NOT NULL DEFAULT true,
    device_identity       text NULL,
    timezone              text NOT NULL CHECK (is_valid_timezone(timezone)),
    direction             text NOT NULL DEFAULT 'unknown' CHECK (direction IN ('in', 'out', 'both', 'unknown')),
    reader_directions     jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(reader_directions) = 'object'),
    credential_types      text[] NOT NULL DEFAULT '{}',
    shared_secret_ref     text NULL,
    allowlist_cidrs       cidr[] NOT NULL DEFAULT '{}',
    enroll_profile        text NULL,
    verify_map            jsonb NOT NULL DEFAULT '{}'::jsonb,
    event_minor_allowlist jsonb NOT NULL DEFAULT '[]'::jsonb,
    ntp_server            text NULL,
    last_seen_at          timestamptz NULL,
    attlog_stamp          bigint NULL,
    last_reconcile_status text NULL CHECK (last_reconcile_status IN ('ok', 'pending_offline', 'failed', 'disabled')),
    reconcile_watermark   timestamptz NULL,
    active                boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_devices_serial UNIQUE (vendor, serial),
    -- Jalur NAT default + cadangan situs VPN (rev 5); middleware opsional.
    CONSTRAINT ck_devices_protocol CHECK (
        (vendor = 'zkteco' AND protocol_family IN ('zkteco_adms', 'middleware'))
        OR (vendor = 'hikvision' AND protocol_family IN ('hik_isup', 'isapi', 'middleware'))
        OR (vendor = 'dahua' AND protocol_family IN ('dahua_autoreg', 'dahua_cgi', 'middleware'))
    )
);
COMMENT ON COLUMN devices.ip IS 'Hanya informatif (alamat sumber terakhir). Server tidak pernah menghubungi alamat ini.';
COMMENT ON COLUMN devices.direction IS 'unknown = belum dikonfigurasi; memblokir matcher_live situs';

CREATE TABLE device_sessions (
    device_id         uuid PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    gateway_instance  text NOT NULL,
    remote_addr       inet NULL,
    firmware          text NULL,
    connected_at      timestamptz NOT NULL,
    last_heartbeat_at timestamptz NOT NULL,
    status            text NOT NULL CHECK (status IN ('online', 'offline'))
);

-- ---------------------------------------------------------------------
-- Karyawan dan identitas perangkat
-- ---------------------------------------------------------------------
CREATE TABLE employees (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id            uuid NOT NULL REFERENCES companies (id),
    employee_no           text NOT NULL,
    full_name             text NOT NULL,
    status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'exited')),
    home_site_id          uuid NULL REFERENCES sites (id),
    exited_at             timestamptz NULL,
    photo_object_key      text NULL,
    photo_retention_until date NULL,
    CONSTRAINT uq_employees_no UNIQUE (company_id, employee_no),
    CONSTRAINT ck_employees_exit CHECK ((status = 'exited') = (exited_at IS NOT NULL))
);

CREATE TABLE employee_device_identities (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id    uuid NOT NULL REFERENCES employees (id),
    vendor         text NOT NULL CHECK (vendor IN ('zkteco', 'hikvision', 'dahua')),
    device_id      uuid NULL REFERENCES devices (id),
    pin            text NOT NULL,
    card_no        text NULL,
    enroll_status  text NOT NULL DEFAULT 'pending'
        CHECK (enroll_status IN ('pending_review', 'pending', 'enrolled', 'failed', 'removed')),
    last_enroll_at timestamptz NULL,
    last_error     text NULL
);
-- UNIQUE biasa menganggap NULL berbeda; PIN bersama (device_id NULL) harus unik sendiri.
CREATE UNIQUE INDEX uq_edi_per_device ON employee_device_identities (vendor, device_id, pin)
    WHERE device_id IS NOT NULL AND enroll_status <> 'removed';
CREATE UNIQUE INDEX uq_edi_shared ON employee_device_identities (vendor, pin)
    WHERE device_id IS NULL AND enroll_status <> 'removed';

-- ---------------------------------------------------------------------
-- Roster
-- ---------------------------------------------------------------------
CREATE TABLE shift_assignments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees (id),
    template_id uuid NOT NULL REFERENCES shift_templates (id),
    civil_date  date NULL,
    start_date  date NULL,
    end_date    date NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_shift_assignments_form CHECK (
        (civil_date IS NOT NULL AND start_date IS NULL AND end_date IS NULL)
        OR (civil_date IS NULL AND start_date IS NOT NULL AND (end_date IS NULL OR end_date >= start_date))
    )
);
CREATE UNIQUE INDEX uq_shift_assignments_daily ON shift_assignments (employee_id, civil_date)
    WHERE civil_date IS NOT NULL;
CREATE INDEX ix_shift_assignments_range ON shift_assignments (employee_id, start_date, end_date)
    WHERE civil_date IS NULL;

CREATE TABLE shift_instances (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id          uuid NOT NULL REFERENCES employees (id),
    template_id          uuid NOT NULL REFERENCES shift_templates (id),
    source_assignment_id uuid NULL REFERENCES shift_assignments (id) ON DELETE SET NULL,
    attendance_date      date NOT NULL,
    timezone             text NOT NULL CHECK (is_valid_timezone(timezone)),
    scheduled_start      timestamptz NOT NULL,
    scheduled_end        timestamptz NOT NULL,
    break_windows        jsonb NOT NULL DEFAULT '[]'::jsonb,
    materialized_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_shift_instances UNIQUE (employee_id, attendance_date, template_id),
    CONSTRAINT ck_shift_instances_span CHECK (scheduled_end > scheduled_start)
);

CREATE TABLE overtime_orders (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id     uuid NOT NULL REFERENCES employees (id),
    attendance_date date NOT NULL,
    quota_minutes   int  NOT NULL CHECK (quota_minutes > 0),
    reason          text NOT NULL,
    status          text NOT NULL DEFAULT 'requested'
        CHECK (status IN ('requested', 'approved', 'rejected', 'cancelled', 'expired')),
    requested_by    text NOT NULL,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    approved_by     text NULL,
    approved_at     timestamptz NULL,
    effective_from  timestamptz NULL,
    pay_policy_id   uuid NOT NULL REFERENCES pay_policies (id),
    flags           text[] NOT NULL DEFAULT '{}',
    cancelled_by    text NULL,
    cancelled_at    timestamptz NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ot_approved_fields CHECK (
        (status = 'approved' AND approved_at IS NOT NULL AND effective_from IS NOT NULL)
        OR (status IN ('requested', 'rejected', 'expired') AND effective_from IS NULL)
        OR status = 'cancelled'
    ),
    CONSTRAINT ck_ot_effective_minute CHECK (effective_from IS NULL OR effective_from = date_trunc('minute', effective_from))
);
CREATE INDEX ix_ot_date ON overtime_orders (employee_id, attendance_date) WHERE status = 'approved';

CREATE TABLE compiled_windows (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id          uuid NOT NULL REFERENCES employees (id),
    attendance_date      date NOT NULL,
    plan_key             text NOT NULL,
    shift_instance_ids   uuid[] NOT NULL DEFAULT '{}',
    overtime_order_ids   uuid[] NOT NULL DEFAULT '{}',
    quota_minutes_total  int NOT NULL DEFAULT 0,
    anchor               timestamptz NULL,
    hard_end             timestamptz NULL,
    timezone             text NOT NULL,
    expected_start       timestamptz NOT NULL,
    expected_end         timestamptz NOT NULL,
    in_open              timestamptz NOT NULL,
    in_close             timestamptz NOT NULL,
    out_open             timestamptz NOT NULL,
    out_close            timestamptz NOT NULL,
    usable_quota_minutes int NOT NULL DEFAULT 0,
    flags                text[] NOT NULL DEFAULT '{}',
    compile_version      bigint NOT NULL,
    current              boolean NOT NULL,
    compiled_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_compiled_windows_order CHECK (in_open <= in_close AND out_open <= out_close)
);
CREATE INDEX ix_compiled_windows_current ON compiled_windows (employee_id, attendance_date) WHERE current;

-- ---------------------------------------------------------------------
-- Punch (append-only; hanya employee_id, door_direction, match_status yang boleh berubah)
-- ---------------------------------------------------------------------
CREATE TABLE punches (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor                    text NOT NULL CHECK (vendor IN ('zkteco', 'hikvision', 'dahua', 'manual')),
    device_sn                 text NOT NULL,
    vendor_event_id           text NULL,
    employee_pin              text NOT NULL,
    employee_id               uuid NULL REFERENCES employees (id),
    occurred_at               timestamptz NOT NULL,
    device_local_time         timestamp NOT NULL,
    verify_mode               text NOT NULL
        CHECK (verify_mode IN ('face', 'fingerprint', 'card', 'palm', 'password', 'qr', 'multi', 'manual', 'unknown')),
    device_reported_direction text NOT NULL DEFAULT 'unknown'
        CHECK (device_reported_direction IN ('in', 'out', 'break', 'unknown')),
    door_direction            text NOT NULL CHECK (door_direction IN ('in', 'out', 'both', 'unknown')),
    door_direction_source     text NOT NULL CHECK (door_direction_source IN ('reader', 'device', 'reclassified', 'manual')),
    reader_id                 text NULL,
    similarity                real NULL,
    liveness                  real NULL,
    raw_payload               jsonb NOT NULL DEFAULT '{}'::jsonb,
    snapshot_discarded        boolean NOT NULL DEFAULT false,
    ingested_at               timestamptz NOT NULL DEFAULT now(),
    idempotency_key           text NOT NULL,
    match_status              text NOT NULL DEFAULT 'pending'
        CHECK (match_status IN ('pending', 'matched', 'ignored', 'exception')),
    CONSTRAINT uq_punches_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX ix_punches_employee_time ON punches (employee_id, occurred_at);
CREATE INDEX ix_punches_device_local ON punches (device_sn, device_local_time);
CREATE INDEX ix_punches_unresolved ON punches (vendor, employee_pin) WHERE employee_id IS NULL;

-- ---------------------------------------------------------------------
-- Hasil mesin: run, sesi berversi, segmen, exception
-- ---------------------------------------------------------------------
CREATE TABLE match_runs (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id          uuid NOT NULL REFERENCES employees (id),
    run_mode             text NOT NULL CHECK (run_mode IN ('live', 'shadow')),
    from_attendance_date date NOT NULL,
    to_attendance_date   date NOT NULL,
    reason               text NOT NULL CHECK (reason IN (
        'punch', 'ot_approved', 'ot_cancelled', 'roster_changed', 'calendar_changed', 'policy_changed',
        'direction_reclassified', 'manual_punch', 're_resolve', 'missing_out_sweeper', 'rollback', 'admin')),
    matcher_version      text NOT NULL,
    started_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz NULL,
    CONSTRAINT ck_match_runs_range CHECK (to_attendance_date >= from_attendance_date)
);

CREATE TABLE work_sessions (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id            uuid NOT NULL REFERENCES employees (id),
    attendance_date        date NOT NULL,
    run_mode               text NOT NULL CHECK (run_mode IN ('live', 'shadow')),
    shift_instance_id      uuid NULL,
    overtime_order_ids     uuid[] NOT NULL DEFAULT '{}',
    in_punch_id            uuid NOT NULL REFERENCES punches (id),
    out_punch_id           uuid NULL REFERENCES punches (id),
    candidate_out_punch_id uuid NULL REFERENCES punches (id),
    provisional_out_at     timestamptz NULL,
    opened_at              timestamptz NOT NULL,
    closed_at              timestamptz NULL,
    status                 text NOT NULL CHECK (status IN ('open', 'closed', 'missing_out')),
    version                int NOT NULL CHECK (version > 0),
    current                boolean NOT NULL,
    superseded_at          timestamptz NULL,
    superseded_by_run_id   uuid NULL REFERENCES match_runs (id),
    match_run_id           uuid NOT NULL REFERENCES match_runs (id),
    late_minutes           int NOT NULL DEFAULT 0 CHECK (late_minutes >= 0),
    early_leave_minutes    int NOT NULL DEFAULT 0 CHECK (early_leave_minutes >= 0),
    result_hash            text NOT NULL,
    CONSTRAINT ck_ws_superseded CHECK (
        (current AND superseded_at IS NULL AND superseded_by_run_id IS NULL)
        OR (NOT current AND superseded_at IS NOT NULL)
    ),
    CONSTRAINT ck_ws_closed CHECK ((status = 'closed') = (out_punch_id IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_ws_candidate CHECK (status = 'open' OR (candidate_out_punch_id IS NULL AND provisional_out_at IS NULL)),
    CONSTRAINT ck_ws_span CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
COMMENT ON COLUMN work_sessions.status IS 'Hasil bisnis; tidak pernah di-update setelah insert. Versi lewat current/superseded_*.';
CREATE UNIQUE INDEX uq_ws_one_open ON work_sessions (employee_id, run_mode) WHERE current AND status = 'open';
CREATE INDEX ix_ws_current ON work_sessions (employee_id, run_mode, attendance_date) WHERE current;

CREATE TABLE work_segments (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    work_session_id    uuid NOT NULL REFERENCES work_sessions (id) ON DELETE CASCADE,
    employee_id        uuid NOT NULL REFERENCES employees (id),
    attendance_date    date NOT NULL,
    run_mode           text NOT NULL CHECK (run_mode IN ('live', 'shadow')),
    civil_date         date NOT NULL,
    kind               text NOT NULL CHECK (kind IN ('regular', 'ot', 'break', 'unpaid')),
    start_at           timestamptz NOT NULL,
    end_at             timestamptz NOT NULL,
    minutes            int NOT NULL CHECK (minutes > 0),
    rate_code          text NOT NULL,
    within_cap         boolean NULL,
    overtime_order_id  uuid NULL REFERENCES overtime_orders (id),
    pay_policy_id      uuid NOT NULL REFERENCES pay_policies (id),
    pay_policy_version int NOT NULL,
    CONSTRAINT ck_segments_span CHECK (end_at > start_at),
    CONSTRAINT ck_segments_cap CHECK ((kind = 'ot') = (within_cap IS NOT NULL))
);
CREATE INDEX ix_segments_session ON work_segments (work_session_id);
CREATE INDEX ix_segments_export ON work_segments (employee_id, run_mode, attendance_date);

CREATE TABLE attendance_exceptions (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id         uuid NULL REFERENCES employees (id),
    attendance_date     date NULL,
    run_mode            text NULL CHECK (run_mode IN ('live', 'shadow')),
    exception_key       text NOT NULL,
    punch_id            uuid NULL REFERENCES punches (id),
    work_session_id     uuid NULL REFERENCES work_sessions (id) ON DELETE SET NULL,
    code                text NOT NULL CHECK (code IN (
        'unresolved_employee', 'unresolved_direction', 'ambiguous_in', 'overlap_unresolved', 'gap_punch',
        'unexpected_in', 'unexpected_out', 'in_outside_window', 'out_outside_window', 'missing_out',
        'invariant_broken', 'roster_ambiguous', 'daily_ot_cap_exceeded', 'weekly_ot_cap_exceeded',
        'min_rest_violation', 'actual_rest_short', 'ot_truncated_by_next_shift', 'long_session',
        'clock_skew', 'late_in', 'ot_without_quota')),
    severity            text NOT NULL CHECK (severity IN ('info', 'warning', 'error')),
    detail              jsonb NOT NULL DEFAULT '{}'::jsonb,
    status              text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'ignored', 'obsolete')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    last_seen_run_id    uuid NULL REFERENCES match_runs (id),
    obsoleted_by_run_id uuid NULL REFERENCES match_runs (id),
    resolved_by         text NULL,
    resolved_at         timestamptz NULL,
    resolution_note     text NULL,
    CONSTRAINT uq_exceptions_key UNIQUE (exception_key),
    -- Exception di luar mesin (materialize, ingress) tidak terikat run_mode.
    CONSTRAINT ck_exceptions_run_mode CHECK (
        run_mode IS NOT NULL OR code IN ('roster_ambiguous', 'unresolved_employee', 'clock_skew')
    ),
    CONSTRAINT ck_exceptions_resolved CHECK ((status IN ('resolved', 'ignored')) = (resolved_at IS NOT NULL))
);
CREATE INDEX ix_exceptions_queue ON attendance_exceptions (severity, created_at) WHERE status = 'open';
CREATE INDEX ix_exceptions_employee ON attendance_exceptions (employee_id, attendance_date);

-- ---------------------------------------------------------------------
-- Kalender
-- ---------------------------------------------------------------------
CREATE TABLE calendar_days (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES companies (id),
    site_id    uuid NULL REFERENCES sites (id),
    civil_date date NOT NULL,
    day_type   text NOT NULL CHECK (day_type IN ('weekday', 'weekly_rest', 'public_holiday')),
    name       text NULL
);
CREATE UNIQUE INDEX uq_cal_site ON calendar_days (site_id, civil_date) WHERE site_id IS NOT NULL;
CREATE UNIQUE INDEX uq_cal_national ON calendar_days (company_id, civil_date) WHERE site_id IS NULL;

-- ---------------------------------------------------------------------
-- Antrean kerja
-- ---------------------------------------------------------------------
CREATE TABLE match_jobs (
    employee_id  uuid PRIMARY KEY REFERENCES employees (id),
    generation   bigint NOT NULL DEFAULT 1,
    rematch_from date NULL,
    enqueued_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN match_jobs.rematch_from IS 'Tanggal bisnis paling awal yang harus dihitung ulang (LEAST dari semua pemicu); NULL = pakai lookback default.';

CREATE TABLE device_commands (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id    uuid NOT NULL REFERENCES devices (id),
    command_type text NOT NULL CHECK (command_type IN (
        'enroll_user', 'enroll_face', 'delete_user', 'reconcile_range', 'set_time', 'reboot', 'sync_config')),
    payload      jsonb NOT NULL DEFAULT '{}'::jsonb,
    status       text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'leased', 'sent', 'acked', 'failed')),
    leased_by    text NULL,
    lease_until  timestamptz NULL,
    attempts     int NOT NULL DEFAULT 0,
    queued_at    timestamptz NOT NULL DEFAULT now(),
    sent_at      timestamptz NULL,
    acked_at     timestamptz NULL,
    last_error   text NULL,
    CONSTRAINT ck_device_commands_lease CHECK ((status = 'leased') = (leased_by IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_device_commands_pending ON device_commands (device_id, queued_at) WHERE status IN ('queued', 'leased');

CREATE TABLE audit_log (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id  text NOT NULL,
    action    text NOT NULL,
    entity    text NOT NULL,
    entity_id text NOT NULL,
    at        timestamptz NOT NULL DEFAULT now(),
    before    jsonb NULL,
    after     jsonb NULL,
    reason    text NULL
);
CREATE INDEX ix_audit_entity ON audit_log (entity, entity_id, at);
